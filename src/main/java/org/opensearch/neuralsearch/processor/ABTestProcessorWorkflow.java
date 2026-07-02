/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.processor;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.opensearch.common.lucene.search.TopDocsAndMaxScore;
import org.opensearch.neuralsearch.processor.interleaving.TeamDraftInterleaver;
import org.opensearch.search.fetch.FetchSearchResult;
import org.opensearch.search.query.QuerySearchResult;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Workflow that applies Team Draft Interleaving to hybrid query results.
 * Takes results from two sub-queries and produces an interleaved result set,
 * returning team membership information for response tagging.
 */
@Log4j2
public class ABTestProcessorWorkflow {

    /**
     * Execute the interleaving workflow on query results from hybrid query.
     *
     * @param querySearchResults query search results from all shards
     * @param fetchSearchResultOptional optional fetch search result for single-shard case
     * @param interleaver the team draft interleaver instance
     * @return InterleavingResult containing team membership mapping (docId -> team index)
     */
    public InterleavingResult execute(
        final List<QuerySearchResult> querySearchResults,
        final Optional<FetchSearchResult> fetchSearchResultOptional,
        final TeamDraftInterleaver interleaver
    ) {
        List<QuerySearchResult> validResults = querySearchResults.stream()
            .filter(result -> Objects.nonNull(result) && Objects.nonNull(result.topDocs()))
            .collect(Collectors.toList());

        if (validResults.isEmpty()) {
            return new InterleavingResult(Map.of());
        }

        List<CompoundTopDocs> queryTopDocs = getQueryTopDocs(validResults);
        Map<Integer, Integer> globalTeamMembership = new HashMap<>();

        for (int shardIndex = 0; shardIndex < validResults.size(); shardIndex++) {
            QuerySearchResult querySearchResult = validResults.get(shardIndex);
            CompoundTopDocs compoundTopDocs = queryTopDocs.get(shardIndex);

            if (compoundTopDocs.getTopDocs() == null || compoundTopDocs.getTopDocs().size() < 2) {
                log.debug("Shard {} does not have enough sub-query results for interleaving, skipping", shardIndex);
                continue;
            }

            int size = computeInterleavingSize(compoundTopDocs);
            TeamDraftInterleaver.Result tdiResult = interleaver.interleave(compoundTopDocs, size);

            List<ScoreDoc> interleavedHits = tdiResult.getInterleavedHits();
            ScoreDoc[] interleavedScoreDocs = interleavedHits.toArray(new ScoreDoc[0]);

            assignInterleavedScores(interleavedScoreDocs);

            Set<Integer> teamA = tdiResult.getTeamA();
            for (ScoreDoc scoreDoc : interleavedScoreDocs) {
                globalTeamMembership.put(scoreDoc.doc, teamA.contains(scoreDoc.doc) ? 0 : 1);
            }

            TotalHits totalHits = new TotalHits(interleavedScoreDocs.length, TotalHits.Relation.EQUAL_TO);
            TopDocs interleavedTopDocs = new TopDocs(totalHits, interleavedScoreDocs);
            float maxScore = interleavedScoreDocs.length > 0 ? interleavedScoreDocs[0].score : 0.0f;
            TopDocsAndMaxScore updatedTopDocsAndMaxScore = new TopDocsAndMaxScore(interleavedTopDocs, maxScore);
            querySearchResult.topDocs(updatedTopDocsAndMaxScore, querySearchResult.sortValueFormats());
        }

        return new InterleavingResult(globalTeamMembership);
    }

    private int computeInterleavingSize(CompoundTopDocs compoundTopDocs) {
        List<TopDocs> topDocsList = compoundTopDocs.getTopDocs();
        int maxA = topDocsList.get(0).scoreDocs != null ? topDocsList.get(0).scoreDocs.length : 0;
        int maxB = topDocsList.get(1).scoreDocs != null ? topDocsList.get(1).scoreDocs.length : 0;
        return maxA + maxB;
    }

    private void assignInterleavedScores(ScoreDoc[] interleavedScoreDocs) {
        for (int i = 0; i < interleavedScoreDocs.length; i++) {
            interleavedScoreDocs[i].score = interleavedScoreDocs.length - i;
        }
    }

    private List<CompoundTopDocs> getQueryTopDocs(final List<QuerySearchResult> querySearchResults) {
        List<CompoundTopDocs> queryTopDocs = querySearchResults.stream()
            .filter(searchResult -> Objects.nonNull(searchResult.topDocs()))
            .map(CompoundTopDocs::new)
            .collect(Collectors.toList());
        if (queryTopDocs.size() != querySearchResults.size()) {
            throw new IllegalStateException(
                String.format(
                    Locale.ROOT,
                    "query results were not formatted correctly by the hybrid query; sizes of querySearchResults [%d] and queryTopDocs [%d] must match",
                    querySearchResults.size(),
                    queryTopDocs.size()
                )
            );
        }
        return queryTopDocs;
    }

    /**
     * Result of the interleaving operation containing team membership mapping.
     * Maps docId to team index (0 = team A, 1 = team B).
     */
    @Getter
    public static class InterleavingResult {
        private final Map<Integer, Integer> teamMembership;

        public InterleavingResult(Map<Integer, Integer> teamMembership) {
            this.teamMembership = Map.copyOf(teamMembership);
        }
    }
}
