/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.processor;

import static org.opensearch.neuralsearch.search.util.HybridSearchResultFormatUtil.isHybridQueryStartStopElement;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.Getter;
import org.opensearch.action.search.QueryPhaseResultConsumer;
import org.opensearch.action.search.SearchPhaseContext;
import org.opensearch.action.search.SearchPhaseName;
import org.opensearch.action.search.SearchPhaseResults;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.neuralsearch.processor.interleaving.TeamDraftInterleaver;
import org.opensearch.neuralsearch.query.ABTestQueryBuilder;
import org.opensearch.search.SearchPhaseResult;
import org.opensearch.search.fetch.FetchSearchResult;
import org.opensearch.search.pipeline.PipelineProcessingContext;
import org.opensearch.search.query.QuerySearchResult;

import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * Processor for implementing Team Draft Interleaving on post-query search results.
 * This processor interleaves results from two sub-queries of a hybrid query using
 * the Team Draft Interleaving algorithm, enabling unbiased A/B testing of search
 * configurations within a single search request.
 *
 * It stores team membership information in the PipelineProcessingContext so the
 * ABTestResponseProcessor can tag each hit with its originating sub-query name.
 */
@Log4j2
@AllArgsConstructor
public class ABTestProcessor extends AbstractScoreHybridizationProcessor {
    public static final String TYPE = "ab-test-processor";
    public static final String ABTEST_TEAM_MEMBERSHIP_KEY = "ab_test_team_membership";
    public static final String ABTEST_QUERY_NAMES_KEY = "ab_test_query_names";
    public static final String ABTEST_TEST_NAME_KEY = "ab_test_test_name";

    @Getter
    private final String tag;
    @Getter
    private final String description;
    private final TeamDraftInterleaver interleaver;
    private final ABTestProcessorWorkflow workflow;

    @Override
    <Result extends SearchPhaseResult> void hybridizeScores(
        SearchPhaseResults<Result> searchPhaseResult,
        SearchPhaseContext searchPhaseContext,
        Optional<PipelineProcessingContext> requestContextOptional
    ) {
        if (shouldSkipProcessor(searchPhaseResult)) {
            log.debug("Query results are not compatible with AB test processor");
            return;
        }
        List<QuerySearchResult> querySearchResults = getQueryPhaseSearchResults(searchPhaseResult);
        Optional<FetchSearchResult> fetchSearchResult = getFetchSearchResults(searchPhaseResult);
        ABTestProcessorWorkflow.InterleavingResult result = workflow.execute(querySearchResults, fetchSearchResult, interleaver);

        requestContextOptional.ifPresent(ctx -> {
            ctx.setAttribute(ABTEST_TEAM_MEMBERSHIP_KEY, result);
            extractAndStoreQueryMetadata(searchPhaseContext, ctx);
        });
    }

    private void extractAndStoreQueryMetadata(SearchPhaseContext searchPhaseContext, PipelineProcessingContext ctx) {
        if (searchPhaseContext.getRequest() == null
            || searchPhaseContext.getRequest().source() == null
            || searchPhaseContext.getRequest().source().query() == null) {
            return;
        }
        QueryBuilder queryBuilder = searchPhaseContext.getRequest().source().query();
        if (queryBuilder instanceof ABTestQueryBuilder abTestQuery) {
            ctx.setAttribute(ABTEST_QUERY_NAMES_KEY, abTestQuery.getQueryNames());
            ctx.setAttribute(ABTEST_TEST_NAME_KEY, abTestQuery.getTestName());
        }
    }

    @Override
    public SearchPhaseName getBeforePhase() {
        return SearchPhaseName.QUERY;
    }

    @Override
    public SearchPhaseName getAfterPhase() {
        return SearchPhaseName.FETCH;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public boolean isIgnoreFailure() {
        return false;
    }

    private <Result extends SearchPhaseResult> boolean shouldSkipProcessor(SearchPhaseResults<Result> searchPhaseResult) {
        if (Objects.isNull(searchPhaseResult) || !(searchPhaseResult instanceof QueryPhaseResultConsumer queryPhaseResultConsumer)) {
            return true;
        }
        return queryPhaseResultConsumer.getAtomicArray().asList().stream().filter(Objects::nonNull).noneMatch(this::isHybridQuery);
    }

    private boolean isHybridQuery(final SearchPhaseResult searchPhaseResult) {
        return Objects.nonNull(searchPhaseResult.queryResult())
            && Objects.nonNull(searchPhaseResult.queryResult().topDocs())
            && Objects.nonNull(searchPhaseResult.queryResult().topDocs().topDocs.scoreDocs)
            && searchPhaseResult.queryResult().topDocs().topDocs.scoreDocs.length > 0
            && isHybridQueryStartStopElement(searchPhaseResult.queryResult().topDocs().topDocs.scoreDocs[0]);
    }

    private <Result extends SearchPhaseResult> List<QuerySearchResult> getQueryPhaseSearchResults(
        final SearchPhaseResults<Result> results
    ) {
        return results.getAtomicArray()
            .asList()
            .stream()
            .map(result -> result == null ? null : result.queryResult())
            .collect(Collectors.toList());
    }

    private <Result extends SearchPhaseResult> Optional<FetchSearchResult> getFetchSearchResults(
        final SearchPhaseResults<Result> searchPhaseResults
    ) {
        Optional<Result> optionalFirstSearchPhaseResult = searchPhaseResults.getAtomicArray().asList().stream().findFirst();
        return optionalFirstSearchPhaseResult.map(SearchPhaseResult::fetchResult);
    }
}
