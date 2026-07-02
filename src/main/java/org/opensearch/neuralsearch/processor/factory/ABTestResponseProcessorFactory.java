/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.processor.factory;

import java.util.Map;

import org.opensearch.neuralsearch.processor.ABTestResponseProcessor;
import org.opensearch.search.pipeline.Processor;
import org.opensearch.search.pipeline.SearchResponseProcessor;

import lombok.extern.log4j.Log4j2;

/**
 * Factory for creating ABTestResponseProcessor instances.
 * No configuration required — all metadata (test name, query names) is extracted
 * from the ab_test query by ABTestProcessor and passed through PipelineProcessingContext.
 */
@Log4j2
public class ABTestResponseProcessorFactory implements Processor.Factory<SearchResponseProcessor> {

    @Override
    public SearchResponseProcessor create(
        final Map<String, Processor.Factory<SearchResponseProcessor>> processorFactories,
        final String tag,
        final String description,
        final boolean ignoreFailure,
        final Map<String, Object> config,
        final Processor.PipelineContext pipelineContext
    ) throws Exception {
        log.info("Creating response processor of type [{}]", ABTestResponseProcessor.TYPE);
        return new ABTestResponseProcessor(description, tag, ignoreFailure);
    }
}
