/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.query;

import static org.opensearch.core.xcontent.ToXContent.EMPTY_PARAMS;

import java.io.IOException;
import java.util.List;

import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.ParseField;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.core.common.io.stream.NamedWriteableAwareStreamInput;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.index.query.MatchAllQueryBuilder;
import org.opensearch.index.query.MatchQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.TermQueryBuilder;

public class ABTestQueryBuilderTests extends OpenSearchQueryTestCase {

    public void testBuilder_whenAddTwoNamedQueries_thenSuccessful() {
        ABTestQueryBuilder builder = new ABTestQueryBuilder();
        builder.add("config_a", new MatchAllQueryBuilder());
        builder.add("config_b", new TermQueryBuilder("field", "value"));
        builder.setTestName("my test");

        assertEquals(2, builder.getQueries().size());
        assertEquals(2, builder.getQueryNames().size());
        assertEquals("config_a", builder.getQueryNames().get(0));
        assertEquals("config_b", builder.getQueryNames().get(1));
        assertEquals("my test", builder.getTestName());
        assertEquals(ABTestQueryBuilder.NAME, builder.getWriteableName());
    }

    public void testBuilder_whenAddNullQuery_thenThrowException() {
        ABTestQueryBuilder builder = new ABTestQueryBuilder();
        expectThrows(IllegalArgumentException.class, () -> builder.add("name", null));
    }

    public void testBuilder_whenAddNullName_thenThrowException() {
        ABTestQueryBuilder builder = new ABTestQueryBuilder();
        expectThrows(IllegalArgumentException.class, () -> builder.add(null, new MatchAllQueryBuilder()));
    }

    public void testBuilder_whenAddEmptyName_thenThrowException() {
        ABTestQueryBuilder builder = new ABTestQueryBuilder();
        expectThrows(IllegalArgumentException.class, () -> builder.add("", new MatchAllQueryBuilder()));
    }

    public void testFromXContent_whenValidInput_thenSuccessful() throws IOException {
        String queryJson = """
            {
                "name": "my experiment",
                "queries": [
                    {
                        "name": "bm25_only",
                        "query": { "match": { "title": "search" } }
                    },
                    {
                        "name": "hybrid_v1",
                        "query": { "match": { "title": "query" } }
                    }
                ]
            }
            """;

        XContentParser parser = createParserForJson(queryJson);
        parser.nextToken();

        ABTestQueryBuilder result = ABTestQueryBuilder.fromXContent(parser);

        assertNotNull(result);
        assertEquals(2, result.getQueries().size());
        assertEquals("bm25_only", result.getQueryNames().get(0));
        assertEquals("hybrid_v1", result.getQueryNames().get(1));
        assertEquals("my experiment", result.getTestName());
    }

    public void testFromXContent_whenMissingSubQueryName_thenThrowException() throws IOException {
        String queryJson = """
            {
                "name": "test",
                "queries": [
                    {
                        "query": { "match": { "title": "search" } }
                    },
                    {
                        "name": "config_b",
                        "query": { "match": { "title": "query" } }
                    }
                ]
            }
            """;

        XContentParser parser = createParserForJson(queryJson);
        parser.nextToken();

        expectThrows(Exception.class, () -> ABTestQueryBuilder.fromXContent(parser));
    }

    public void testFromXContent_whenOnlyOneQuery_thenThrowException() throws IOException {
        String queryJson = """
            {
                "name": "test",
                "queries": [
                    {
                        "name": "config_a",
                        "query": { "match": { "title": "search" } }
                    }
                ]
            }
            """;

        XContentParser parser = createParserForJson(queryJson);
        parser.nextToken();

        expectThrows(Exception.class, () -> ABTestQueryBuilder.fromXContent(parser));
    }

    public void testSerialization_whenSerializeAndDeserialize_thenSuccessful() throws IOException {
        ABTestQueryBuilder original = new ABTestQueryBuilder();
        original.add("bm25", new MatchQueryBuilder("title", "search"));
        original.add("neural", new TermQueryBuilder("status", "active"));
        original.setTestName("experiment_1");

        BytesStreamOutput output = new BytesStreamOutput();
        output.writeNamedWriteable(original);

        List<NamedWriteableRegistry.Entry> entries = List.of(
            new NamedWriteableRegistry.Entry(QueryBuilder.class, ABTestQueryBuilder.NAME, ABTestQueryBuilder::new),
            new NamedWriteableRegistry.Entry(QueryBuilder.class, MatchQueryBuilder.NAME, MatchQueryBuilder::new),
            new NamedWriteableRegistry.Entry(QueryBuilder.class, TermQueryBuilder.NAME, TermQueryBuilder::new)
        );
        NamedWriteableRegistry registry = new NamedWriteableRegistry(entries);

        NamedWriteableAwareStreamInput input = new NamedWriteableAwareStreamInput(output.bytes().streamInput(), registry);

        ABTestQueryBuilder deserialized = (ABTestQueryBuilder) input.readNamedWriteable(QueryBuilder.class);
        assertEquals(original.getQueries().size(), deserialized.getQueries().size());
        assertEquals(original.getQueryNames(), deserialized.getQueryNames());
        assertEquals("experiment_1", deserialized.getTestName());
    }

    public void testToXContent_whenValidBuilder_thenCorrectOutput() throws IOException {
        ABTestQueryBuilder builder = new ABTestQueryBuilder();
        builder.add("config_a", new MatchAllQueryBuilder());
        builder.add("config_b", new MatchAllQueryBuilder());
        builder.setTestName("my test");

        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder();
        builder.toXContent(xContentBuilder, EMPTY_PARAMS);
        String json = BytesReference.bytes(xContentBuilder).utf8ToString();

        assertTrue(json.contains("ab_test"));
        assertTrue(json.contains("queries"));
        assertTrue(json.contains("config_a"));
        assertTrue(json.contains("config_b"));
        assertTrue(json.contains("my test"));
    }

    public void testEquals_whenSameQueries_thenEqual() {
        ABTestQueryBuilder builder1 = new ABTestQueryBuilder();
        builder1.add("a", new MatchAllQueryBuilder());
        builder1.add("b", new TermQueryBuilder("field", "value"));
        builder1.setTestName("test");

        ABTestQueryBuilder builder2 = new ABTestQueryBuilder();
        builder2.add("a", new MatchAllQueryBuilder());
        builder2.add("b", new TermQueryBuilder("field", "value"));
        builder2.setTestName("test");

        assertEquals(builder1, builder2);
        assertEquals(builder1.hashCode(), builder2.hashCode());
    }

    public void testEquals_whenDifferentNames_thenNotEqual() {
        ABTestQueryBuilder builder1 = new ABTestQueryBuilder();
        builder1.add("a", new MatchAllQueryBuilder());
        builder1.add("b", new TermQueryBuilder("field", "value"));

        ABTestQueryBuilder builder2 = new ABTestQueryBuilder();
        builder2.add("x", new MatchAllQueryBuilder());
        builder2.add("y", new TermQueryBuilder("field", "value"));

        assertNotEquals(builder1, builder2);
    }

    private XContentParser createParserForJson(String json) throws IOException {
        NamedXContentRegistry namedXContentRegistry = new NamedXContentRegistry(
            List.of(
                new NamedXContentRegistry.Entry(
                    QueryBuilder.class,
                    new ParseField(MatchQueryBuilder.NAME),
                    MatchQueryBuilder::fromXContent
                ),
                new NamedXContentRegistry.Entry(
                    QueryBuilder.class,
                    new ParseField(MatchAllQueryBuilder.NAME),
                    MatchAllQueryBuilder::fromXContent
                ),
                new NamedXContentRegistry.Entry(QueryBuilder.class, new ParseField(TermQueryBuilder.NAME), TermQueryBuilder::fromXContent)
            )
        );
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder().value(json);
        XContentParser parser = createParser(namedXContentRegistry, xContentBuilder.contentType().xContent(), new BytesArray(json));
        return parser;
    }
}
