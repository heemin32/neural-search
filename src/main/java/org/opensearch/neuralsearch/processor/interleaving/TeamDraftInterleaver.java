/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.processor.interleaving;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.opensearch.neuralsearch.processor.CompoundTopDocs;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Implements Team Draft Interleaving (TDI) algorithm for A/B testing search configurations.
 *
 * TDI merges two ranked result lists into a single interleaved list while tracking which
 * "team" (sub-query) each document belongs to. A fair coin flip each round
 * determines which list picks first, ensuring unbiased presentation order.
 *
 * Reference: Radlinski and Craswell, "Optimized Interleaving for Online Retrieval Evaluation" (WSDM 2013)
 */
@NoArgsConstructor
public class TeamDraftInterleaver {

    /**
     * Interleaves results from two sub-queries within a CompoundTopDocs using the Team Draft algorithm.
     *
     * @param compoundTopDocs the compound top docs containing results from multiple sub-queries
     * @param size maximum number of results in the interleaved list
     * @return Result containing the interleaved ScoreDocs and team membership sets
     */
    public Result interleave(CompoundTopDocs compoundTopDocs, int size) {
        List<TopDocs> topDocsList = compoundTopDocs.getTopDocs();
        if (topDocsList == null || topDocsList.size() < 2) {
            throw new IllegalArgumentException(
                "Team Draft Interleaving requires exactly 2 sub-queries, but got " + (topDocsList == null ? 0 : topDocsList.size())
            );
        }

        ScoreDoc[] hitsA = topDocsList.get(0).scoreDocs;
        ScoreDoc[] hitsB = topDocsList.get(1).scoreDocs;

        if (hitsA == null) hitsA = new ScoreDoc[0];
        if (hitsB == null) hitsB = new ScoreDoc[0];

        List<ScoreDoc> interleaved = new ArrayList<>(size);
        Set<Integer> teamA = new HashSet<>();
        Set<Integer> teamB = new HashSet<>();
        Set<Integer> seen = new HashSet<>();
        int ptrA = 0;
        int ptrB = 0;

        while (interleaved.size() < size) {
            boolean aFirst = ThreadLocalRandom.current().nextBoolean();
            ScoreDoc[] firstHits = aFirst ? hitsA : hitsB;
            ScoreDoc[] secondHits = aFirst ? hitsB : hitsA;
            Set<Integer> firstTeam = aFirst ? teamA : teamB;
            Set<Integer> secondTeam = aFirst ? teamB : teamA;
            int firstPtr = aFirst ? ptrA : ptrB;
            int secondPtr = aFirst ? ptrB : ptrA;

            while (firstPtr < firstHits.length && seen.contains(firstHits[firstPtr].doc)) {
                firstPtr++;
            }
            boolean pickedFirst = false;
            if (firstPtr < firstHits.length) {
                ScoreDoc pick = firstHits[firstPtr];
                interleaved.add(pick);
                firstTeam.add(pick.doc);
                seen.add(pick.doc);
                firstPtr++;
                pickedFirst = true;
            }
            if (interleaved.size() >= size) {
                if (aFirst) {
                    ptrA = firstPtr;
                    ptrB = secondPtr;
                } else {
                    ptrB = firstPtr;
                    ptrA = secondPtr;
                }
                break;
            }

            while (secondPtr < secondHits.length && seen.contains(secondHits[secondPtr].doc)) {
                secondPtr++;
            }
            boolean pickedSecond = false;
            if (secondPtr < secondHits.length) {
                ScoreDoc pick = secondHits[secondPtr];
                interleaved.add(pick);
                secondTeam.add(pick.doc);
                seen.add(pick.doc);
                secondPtr++;
                pickedSecond = true;
            }

            if (aFirst) {
                ptrA = firstPtr;
                ptrB = secondPtr;
            } else {
                ptrB = firstPtr;
                ptrA = secondPtr;
            }

            if (!pickedFirst && !pickedSecond) {
                break;
            }
        }
        return new Result(interleaved, teamA, teamB);
    }

    @Getter
    public static class Result {
        private final List<ScoreDoc> interleavedHits;
        private final Set<Integer> teamA;
        private final Set<Integer> teamB;

        public Result(List<ScoreDoc> interleavedHits, Set<Integer> teamA, Set<Integer> teamB) {
            this.interleavedHits = List.copyOf(interleavedHits);
            this.teamA = Set.copyOf(teamA);
            this.teamB = Set.copyOf(teamB);
        }
    }
}
