/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.processor.factory;

import java.util.Map;

import org.opensearch.neuralsearch.processor.ABTestProcessor;
import org.opensearch.neuralsearch.processor.ABTestProcessorWorkflow;
import org.opensearch.neuralsearch.processor.interleaving.TeamDraftInterleaver;
import org.opensearch.search.pipeline.Processor;
import org.opensearch.search.pipeline.SearchPhaseResultsProcessor;

import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * Factory class to instantiate ABTestProcessor.
 */
@AllArgsConstructor
@Log4j2
public class ABTestProcessorFactory implements Processor.Factory<SearchPhaseResultsProcessor> {

    private final ABTestProcessorWorkflow abTestProcessorWorkflow;

    @Override
    public SearchPhaseResultsProcessor create(
        final Map<String, Processor.Factory<SearchPhaseResultsProcessor>> processorFactories,
        final String tag,
        final String description,
        final boolean ignoreFailure,
        final Map<String, Object> config,
        final Processor.PipelineContext pipelineContext
    ) throws Exception {
        TeamDraftInterleaver interleaver = new TeamDraftInterleaver();
        log.info("Creating search phase results processor of type [{}]", ABTestProcessor.TYPE);
        return new ABTestProcessor(tag, description, interleaver, abTestProcessorWorkflow);
    }
}
