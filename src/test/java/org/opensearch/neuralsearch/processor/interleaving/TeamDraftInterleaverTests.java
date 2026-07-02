/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.processor.interleaving;

import java.util.List;
import java.util.Set;

import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.opensearch.neuralsearch.processor.CompoundTopDocs;
import org.opensearch.neuralsearch.processor.SearchShard;
import org.opensearch.neuralsearch.query.OpenSearchQueryTestCase;

public class TeamDraftInterleaverTests extends OpenSearchQueryTestCase {
    private static final SearchShard SEARCH_SHARD = new SearchShard("test_index", 0, "12345678");

    private final TeamDraftInterleaver interleaver = new TeamDraftInterleaver();

    public void testInterleave_whenTwoSubQueries_thenResultContainsAllDocs() {
        ScoreDoc[] hitsA = new ScoreDoc[] { new ScoreDoc(1, 5.0f), new ScoreDoc(2, 4.0f), new ScoreDoc(3, 3.0f) };
        ScoreDoc[] hitsB = new ScoreDoc[] { new ScoreDoc(4, 5.0f), new ScoreDoc(5, 4.0f), new ScoreDoc(6, 3.0f) };

        TopDocs topDocsA = new TopDocs(new TotalHits(3, TotalHits.Relation.EQUAL_TO), hitsA);
        TopDocs topDocsB = new TopDocs(new TotalHits(3, TotalHits.Relation.EQUAL_TO), hitsB);

        CompoundTopDocs compoundTopDocs = new CompoundTopDocs(
            new TotalHits(6, TotalHits.Relation.EQUAL_TO),
            List.of(topDocsA, topDocsB),
            false,
            SEARCH_SHARD
        );

        TeamDraftInterleaver.Result result = interleaver.interleave(compoundTopDocs, 6);

        assertEquals(6, result.getInterleavedHits().size());
        Set<Integer> allDocs = Set.of(1, 2, 3, 4, 5, 6);
        for (ScoreDoc doc : result.getInterleavedHits()) {
            assertTrue("doc " + doc.doc + " should be in expected set", allDocs.contains(doc.doc));
        }
        // team assignments should cover all docs
        assertEquals(3, result.getTeamA().size());
        assertEquals(3, result.getTeamB().size());
    }

    public void testInterleave_whenOverlappingDocs_thenNoDuplicates() {
        ScoreDoc[] hitsA = new ScoreDoc[] { new ScoreDoc(1, 5.0f), new ScoreDoc(2, 4.0f), new ScoreDoc(3, 3.0f) };
        ScoreDoc[] hitsB = new ScoreDoc[] { new ScoreDoc(2, 5.0f), new ScoreDoc(3, 4.0f), new ScoreDoc(4, 3.0f) };

        TopDocs topDocsA = new TopDocs(new TotalHits(3, TotalHits.Relation.EQUAL_TO), hitsA);
        TopDocs topDocsB = new TopDocs(new TotalHits(3, TotalHits.Relation.EQUAL_TO), hitsB);

        CompoundTopDocs compoundTopDocs = new CompoundTopDocs(
            new TotalHits(6, TotalHits.Relation.EQUAL_TO),
            List.of(topDocsA, topDocsB),
            false,
            SEARCH_SHARD
        );

        TeamDraftInterleaver.Result result = interleaver.interleave(compoundTopDocs, 6);

        // docs 2 and 3 overlap, so we should have at most 4 unique docs
        assertEquals(4, result.getInterleavedHits().size());
        Set<Integer> seenDocs = new java.util.HashSet<>();
        for (ScoreDoc doc : result.getInterleavedHits()) {
            assertTrue("no duplicate doc ids", seenDocs.add(doc.doc));
        }
    }

    public void testInterleave_whenSizeLimited_thenRespectLimit() {
        ScoreDoc[] hitsA = new ScoreDoc[] { new ScoreDoc(1, 5.0f), new ScoreDoc(2, 4.0f), new ScoreDoc(3, 3.0f) };
        ScoreDoc[] hitsB = new ScoreDoc[] { new ScoreDoc(4, 5.0f), new ScoreDoc(5, 4.0f), new ScoreDoc(6, 3.0f) };

        TopDocs topDocsA = new TopDocs(new TotalHits(3, TotalHits.Relation.EQUAL_TO), hitsA);
        TopDocs topDocsB = new TopDocs(new TotalHits(3, TotalHits.Relation.EQUAL_TO), hitsB);

        CompoundTopDocs compoundTopDocs = new CompoundTopDocs(
            new TotalHits(6, TotalHits.Relation.EQUAL_TO),
            List.of(topDocsA, topDocsB),
            false,
            SEARCH_SHARD
        );

        TeamDraftInterleaver.Result result = interleaver.interleave(compoundTopDocs, 3);

        assertEquals(3, result.getInterleavedHits().size());
    }

    public void testInterleave_whenLessThanTwoSubQueries_thenThrowException() {
        ScoreDoc[] hitsA = new ScoreDoc[] { new ScoreDoc(1, 5.0f) };
        TopDocs topDocsA = new TopDocs(new TotalHits(1, TotalHits.Relation.EQUAL_TO), hitsA);

        CompoundTopDocs compoundTopDocs = new CompoundTopDocs(
            new TotalHits(1, TotalHits.Relation.EQUAL_TO),
            List.of(topDocsA),
            false,
            SEARCH_SHARD
        );

        expectThrows(IllegalArgumentException.class, () -> interleaver.interleave(compoundTopDocs, 1));
    }

    public void testInterleave_whenEmptyResults_thenReturnEmpty() {
        ScoreDoc[] hitsA = new ScoreDoc[0];
        ScoreDoc[] hitsB = new ScoreDoc[0];

        TopDocs topDocsA = new TopDocs(new TotalHits(0, TotalHits.Relation.EQUAL_TO), hitsA);
        TopDocs topDocsB = new TopDocs(new TotalHits(0, TotalHits.Relation.EQUAL_TO), hitsB);

        CompoundTopDocs compoundTopDocs = new CompoundTopDocs(
            new TotalHits(0, TotalHits.Relation.EQUAL_TO),
            List.of(topDocsA, topDocsB),
            false,
            SEARCH_SHARD
        );

        TeamDraftInterleaver.Result result = interleaver.interleave(compoundTopDocs, 10);

        assertEquals(0, result.getInterleavedHits().size());
        assertEquals(0, result.getTeamA().size());
        assertEquals(0, result.getTeamB().size());
    }
}
