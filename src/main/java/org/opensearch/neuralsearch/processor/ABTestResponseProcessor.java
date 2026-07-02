/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.processor;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.search.SearchHit;
import org.opensearch.search.SearchHits;
import org.opensearch.search.pipeline.PipelineProcessingContext;
import org.opensearch.search.pipeline.SearchResponseProcessor;

import static org.opensearch.neuralsearch.processor.ABTestProcessor.ABTEST_QUERY_NAMES_KEY;
import static org.opensearch.neuralsearch.processor.ABTestProcessor.ABTEST_TEAM_MEMBERSHIP_KEY;
import static org.opensearch.neuralsearch.processor.ABTestProcessor.ABTEST_TEST_NAME_KEY;

/**
 * Response processor that tags each search hit with metadata indicating which sub-query
 * (team) produced it during A/B test interleaving.
 *
 * Reads team membership, query names, and test name from the PipelineProcessingContext
 * (stored by ABTestProcessor) and adds "_ab_test_query_name" and "_ab_test_name" to each hit's source.
 * No configuration needed — all metadata comes from the ab_test query itself.
 */
@Getter
@AllArgsConstructor
@Log4j2
public class ABTestResponseProcessor implements SearchResponseProcessor {

    public static final String TYPE = "ab-test-response-processor";
    public static final String AB_TEST_QUERY_NAME_FIELD = "_ab_test_query_name";
    public static final String AB_TEST_NAME_FIELD = "_ab_test_name";

    private final String description;
    private final String tag;
    private final boolean ignoreFailure;

    @Override
    public SearchResponse processResponse(SearchRequest request, SearchResponse response) {
        return processResponse(request, response, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public SearchResponse processResponse(
        final SearchRequest request,
        final SearchResponse response,
        final PipelineProcessingContext requestContext
    ) {
        if (Objects.isNull(requestContext) || Objects.isNull(requestContext.getAttribute(ABTEST_TEAM_MEMBERSHIP_KEY))) {
            return response;
        }

        ABTestProcessorWorkflow.InterleavingResult interleavingResult = (ABTestProcessorWorkflow.InterleavingResult) requestContext
            .getAttribute(ABTEST_TEAM_MEMBERSHIP_KEY);
        Map<Integer, Integer> teamMembership = interleavingResult.getTeamMembership();

        List<String> queryNames = (List<String>) requestContext.getAttribute(ABTEST_QUERY_NAMES_KEY);
        String testName = (String) requestContext.getAttribute(ABTEST_TEST_NAME_KEY);

        SearchHits searchHits = response.getHits();
        if (searchHits == null || searchHits.getHits() == null) {
            return response;
        }

        for (SearchHit hit : searchHits.getHits()) {
            Integer teamIndex = teamMembership.get(hit.docId());
            Map<String, Object> sourceMap = hit.getSourceAsMap();
            if (sourceMap != null) {
                if (teamIndex != null && queryNames != null && teamIndex >= 0 && teamIndex < queryNames.size()) {
                    sourceMap.put(AB_TEST_QUERY_NAME_FIELD, queryNames.get(teamIndex));
                }
                if (testName != null) {
                    sourceMap.put(AB_TEST_NAME_FIELD, testName);
                }
            }
        }
        return response;
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
