/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.search;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.GroupShardsIterator;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.indices.IndexClosedException;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.SearchService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static org.elasticsearch.action.search.SearchType.QUERY_AND_FETCH;
import static org.elasticsearch.action.search.SearchType.QUERY_THEN_FETCH;

public class TransportSearchAction extends HandledTransportAction<SearchRequest, SearchResponse> {

    /** The maximum number of shards for a single search request. */
    public static final Setting<Long> SHARD_COUNT_LIMIT_SETTING = Setting.longSetting(
            "action.search.shard_count.limit", 1000L, 1L, Property.Dynamic, Property.NodeScope);

    private final ClusterService clusterService;
    private final SearchTransportService searchTransportService;
    private final SearchPhaseController searchPhaseController;

    @Inject
    public TransportSearchAction(Settings settings, ThreadPool threadPool, BigArrays bigArrays, ScriptService scriptService,
                                 TransportService transportService, SearchService searchService,
                                 ClusterService clusterService, ActionFilters actionFilters, IndexNameExpressionResolver
                                             indexNameExpressionResolver) {
        super(settings, SearchAction.NAME, threadPool, transportService, actionFilters, indexNameExpressionResolver, SearchRequest::new);
        this.searchPhaseController = new SearchPhaseController(settings, bigArrays, scriptService, clusterService);;
        this.searchTransportService = new SearchTransportService(settings, transportService);
        SearchTransportService.registerRequestHandler(transportService, searchService);
        this.clusterService = clusterService;
    }

    @Override
    protected void doExecute(SearchRequest searchRequest, ActionListener<SearchResponse> listener) {
        // pure paranoia if time goes backwards we are at least positive
        final long startTimeInMillis = Math.max(0, System.currentTimeMillis());
        ClusterState clusterState = clusterService.state();
        clusterState.blocks().globalBlockedRaiseException(ClusterBlockLevel.READ);

        // TODO: I think startTime() should become part of ActionRequest and that should be used both for index name
        // date math expressions and $now in scripts. This way all apis will deal with now in the same way instead
        // of just for the _search api
        String[] concreteIndices = indexNameExpressionResolver.concreteIndexNames(clusterState, searchRequest.indicesOptions(),
            startTimeInMillis, searchRequest.indices());
        Map<String, String[]> filteringAliasLookup = new HashMap<>();

        for (String index : concreteIndices) {
            clusterState.blocks().indexBlockedRaiseException(ClusterBlockLevel.READ, index);
            filteringAliasLookup.put(index, indexNameExpressionResolver.filteringAliases(clusterState,
                index, searchRequest.indices()));
        }

        Map<String, Set<String>> routingMap = indexNameExpressionResolver.resolveSearchRouting(clusterState, searchRequest.routing(),
            searchRequest.indices());
        GroupShardsIterator shardIterators = clusterService.operationRouting().searchShards(clusterState, concreteIndices, routingMap,
            searchRequest.preference());
        failIfOverShardCountLimit(clusterService, shardIterators.size());

        // optimize search type for cases where there is only one shard group to search on
        try {
            if (shardIterators.size() == 1) {
                // if we only have one group, then we always want Q_A_F, no need for DFS, and no need to do THEN since we hit one shard
                searchRequest.searchType(QUERY_AND_FETCH);
            }
            if (searchRequest.isSuggestOnly()) {
                // disable request cache if we have only suggest
                searchRequest.requestCache(false);
                switch (searchRequest.searchType()) {
                    case DFS_QUERY_AND_FETCH:
                    case DFS_QUERY_THEN_FETCH:
                        // convert to Q_T_F if we have only suggest
                        searchRequest.searchType(QUERY_THEN_FETCH);
                        break;
                }
            }
        } catch (IndexNotFoundException | IndexClosedException e) {
            // ignore these failures, we will notify the search response if its really the case from the actual action
        } catch (Exception e) {
            logger.debug("failed to optimize search type, continue as normal", e);
        }

        searchAsyncAction(searchRequest, shardIterators, startTimeInMillis, clusterState, Collections.unmodifiableMap(filteringAliasLookup)
            , listener).start();
    }

    private AbstractSearchAsyncAction searchAsyncAction(SearchRequest searchRequest, GroupShardsIterator shardIterators, long startTime,
                                                        ClusterState state,  Map<String, String[]> filteringAliasLookup,
                                                        ActionListener<SearchResponse> listener) {
        final Function<String, DiscoveryNode> nodesLookup = state.nodes()::get;
        final long clusterStateVersion = state.version();
        Executor executor = threadPool.executor(ThreadPool.Names.SEARCH);
        AbstractSearchAsyncAction searchAsyncAction;
        switch(searchRequest.searchType()) {
            case DFS_QUERY_THEN_FETCH:
                searchAsyncAction = new SearchDfsQueryThenFetchAsyncAction(logger, searchTransportService, nodesLookup,
                    filteringAliasLookup, searchPhaseController, executor, searchRequest, listener, shardIterators, startTime,
                    clusterStateVersion);
                break;
            case QUERY_THEN_FETCH:
                searchAsyncAction = new SearchQueryThenFetchAsyncAction(logger, searchTransportService, nodesLookup,
                    filteringAliasLookup, searchPhaseController, executor, searchRequest, listener, shardIterators, startTime,
                    clusterStateVersion);
                break;
            case DFS_QUERY_AND_FETCH:
                searchAsyncAction = new SearchDfsQueryAndFetchAsyncAction(logger, searchTransportService, nodesLookup,
                    filteringAliasLookup, searchPhaseController, executor, searchRequest, listener, shardIterators, startTime,
                    clusterStateVersion);
                break;
            case QUERY_AND_FETCH:
                searchAsyncAction = new SearchQueryAndFetchAsyncAction(logger, searchTransportService, nodesLookup,
                    filteringAliasLookup, searchPhaseController, executor, searchRequest, listener, shardIterators, startTime,
                    clusterStateVersion);
                break;
            default:
                throw new IllegalStateException("Unknown search type: [" + searchRequest.searchType() + "]");
        }
        return searchAsyncAction;
    }

    private void failIfOverShardCountLimit(ClusterService clusterService, int shardCount) {
        final long shardCountLimit = clusterService.getClusterSettings().get(SHARD_COUNT_LIMIT_SETTING);
        if (shardCount > shardCountLimit) {
            throw new IllegalArgumentException("Trying to query " + shardCount + " shards, which is over the limit of "
                + shardCountLimit + ". This limit exists because querying many shards at the same time can make the "
                + "job of the coordinating node very CPU and/or memory intensive. It is usually a better idea to "
                + "have a smaller number of larger shards. Update [" + SHARD_COUNT_LIMIT_SETTING.getKey()
                + "] to a greater value if you really want to query that many shards at the same time.");
        }
    }

}
