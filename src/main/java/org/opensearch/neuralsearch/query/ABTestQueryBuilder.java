/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.lucene.search.Query;
import org.opensearch.common.lucene.search.Queries;
import org.opensearch.core.ParseField;
import org.opensearch.core.common.ParsingException;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.index.query.AbstractQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryRewriteContext;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.index.query.QueryShardException;
import org.opensearch.index.query.QueryBuilderVisitor;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

import org.apache.lucene.search.BooleanClause.Occur;

/**
 * Query builder for A/B testing with Team Draft Interleaving.
 * Takes exactly 2 named sub-queries representing competing search configurations.
 * Results are interleaved using the Team Draft algorithm when used with the ab-test-processor.
 *
 * Example usage:
 * {
 *     "query": {
 *         "ab_test": {
 *             "name": "my test",
 *             "queries": [
 *                 {
 *                     "name": "bm25_only",
 *                     "query": { "match": { "title": "wild west" } }
 *                 },
 *                 {
 *                     "name": "hybrid_v1",
 *                     "query": { "hybrid": { "queries": [...] } }
 *                 }
 *             ]
 *         }
 *     }
 * }
 */
@Log4j2
@Getter
@NoArgsConstructor
public final class ABTestQueryBuilder extends AbstractQueryBuilder<ABTestQueryBuilder> {
    public static final String NAME = "ab_test";

    private static final ParseField QUERIES_FIELD = new ParseField("queries");
    private static final ParseField TEST_NAME_FIELD = new ParseField("name");
    private static final ParseField QUERY_FIELD = new ParseField("query");

    private final List<QueryBuilder> queries = new ArrayList<>();
    private final List<String> queryNames = new ArrayList<>();

    @Setter
    private String testName;

    public static final int REQUIRED_NUMBER_OF_SUB_QUERIES = 2;

    public ABTestQueryBuilder(StreamInput in) throws IOException {
        super(in);
        queries.addAll(in.readNamedWriteableList(QueryBuilder.class));
        queryNames.addAll(in.readStringList());
        testName = in.readOptionalString();
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeNamedWriteableList(queries);
        out.writeStringCollection(queryNames);
        out.writeOptionalString(testName);
    }

    public ABTestQueryBuilder add(String name, QueryBuilder queryBuilder) {
        if (queryBuilder == null) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, "inner %s query clause cannot be null", NAME));
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, "inner %s query clause must have a name", NAME));
        }
        queries.add(queryBuilder);
        queryNames.add(name);
        return this;
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        if (testName != null) {
            builder.field(TEST_NAME_FIELD.getPreferredName(), testName);
        }
        builder.startArray(QUERIES_FIELD.getPreferredName());
        for (int i = 0; i < queries.size(); i++) {
            builder.startObject();
            builder.field(TEST_NAME_FIELD.getPreferredName(), queryNames.get(i));
            builder.field(QUERY_FIELD.getPreferredName());
            queries.get(i).toXContent(builder, params);
            builder.endObject();
        }
        builder.endArray();
        printBoostAndQueryName(builder);
        builder.endObject();
    }

    @Override
    protected Query doToQuery(QueryShardContext queryShardContext) throws IOException {
        Collection<Query> queryCollection = toQueries(queries, queryShardContext);
        if (queryCollection.isEmpty()) {
            return Queries.newMatchNoDocsQuery(String.format(Locale.ROOT, "no clauses for %s query", NAME));
        }
        HybridQueryContext hybridQueryContext = HybridQueryContext.builder().build();
        return new HybridQuery(queryCollection, hybridQueryContext);
    }

    public static ABTestQueryBuilder fromXContent(XContentParser parser) throws IOException {
        float boost = AbstractQueryBuilder.DEFAULT_BOOST;
        final List<QueryBuilder> queries = new ArrayList<>();
        final List<String> subQueryNames = new ArrayList<>();
        String queryName = null;
        String testName = null;

        String currentFieldName = null;
        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token == XContentParser.Token.START_ARRAY) {
                if (QUERIES_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                        if (token != XContentParser.Token.START_OBJECT) {
                            throw new ParsingException(
                                parser.getTokenLocation(),
                                String.format(Locale.ROOT, "[%s] each entry in 'queries' must be an object with 'name' and 'query'", NAME)
                            );
                        }
                        String subQueryName = null;
                        QueryBuilder subQuery = null;
                        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                            if (token == XContentParser.Token.FIELD_NAME) {
                                String field = parser.currentName();
                                if (TEST_NAME_FIELD.match(field, parser.getDeprecationHandler())) {
                                    parser.nextToken();
                                    subQueryName = parser.text();
                                } else if (QUERY_FIELD.match(field, parser.getDeprecationHandler())) {
                                    parser.nextToken();
                                    subQuery = parseInnerQueryBuilder(parser);
                                } else {
                                    throw new ParsingException(
                                        parser.getTokenLocation(),
                                        String.format(Locale.ROOT, "[%s] query entry does not support field [%s]", NAME, field)
                                    );
                                }
                            }
                        }
                        if (subQueryName == null || subQueryName.isEmpty()) {
                            throw new ParsingException(
                                parser.getTokenLocation(),
                                String.format(Locale.ROOT, "[%s] each query entry must have a 'name' field", NAME)
                            );
                        }
                        if (subQuery == null) {
                            throw new ParsingException(
                                parser.getTokenLocation(),
                                String.format(Locale.ROOT, "[%s] each query entry must have a 'query' field", NAME)
                            );
                        }
                        subQueryNames.add(subQueryName);
                        queries.add(subQuery);
                    }
                } else {
                    throw new ParsingException(
                        parser.getTokenLocation(),
                        String.format(Locale.ROOT, "[%s] query does not support [%s]", NAME, currentFieldName)
                    );
                }
            } else if (token == XContentParser.Token.VALUE_STRING) {
                if (TEST_NAME_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    testName = parser.text();
                } else if (AbstractQueryBuilder.NAME_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    queryName = parser.text();
                } else {
                    throw new ParsingException(
                        parser.getTokenLocation(),
                        String.format(Locale.ROOT, "[%s] query does not support [%s]", NAME, currentFieldName)
                    );
                }
            } else if (token == XContentParser.Token.VALUE_NUMBER) {
                if (AbstractQueryBuilder.BOOST_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    boost = parser.floatValue();
                    if (boost != DEFAULT_BOOST) {
                        throw new ParsingException(
                            parser.getTokenLocation(),
                            String.format(Locale.ROOT, "[%s] query does not support [%s]", NAME, BOOST_FIELD)
                        );
                    }
                } else {
                    throw new ParsingException(
                        parser.getTokenLocation(),
                        String.format(Locale.ROOT, "[%s] query does not support [%s]", NAME, currentFieldName)
                    );
                }
            } else {
                throw new ParsingException(
                    parser.getTokenLocation(),
                    String.format(Locale.ROOT, "[%s] query does not support [%s]", NAME, currentFieldName)
                );
            }
        }

        if (queries.size() != REQUIRED_NUMBER_OF_SUB_QUERIES) {
            throw new ParsingException(
                parser.getTokenLocation(),
                String.format(Locale.ROOT, "[%s] requires exactly %d sub-queries in 'queries' field", NAME, REQUIRED_NUMBER_OF_SUB_QUERIES)
            );
        }

        ABTestQueryBuilder queryBuilder = new ABTestQueryBuilder();
        queryBuilder.queryName(queryName);
        queryBuilder.boost(boost);
        queryBuilder.setTestName(testName);
        for (int i = 0; i < queries.size(); i++) {
            queryBuilder.add(subQueryNames.get(i), queries.get(i));
        }
        return queryBuilder;
    }

    @Override
    protected QueryBuilder doRewrite(QueryRewriteContext queryShardContext) throws IOException {
        ABTestQueryBuilder newBuilder = new ABTestQueryBuilder();
        boolean changed = false;
        for (int i = 0; i < queries.size(); i++) {
            QueryBuilder result = queries.get(i).rewrite(queryShardContext);
            if (result != queries.get(i)) {
                changed = true;
            }
            newBuilder.add(queryNames.get(i), result);
        }
        if (changed) {
            newBuilder.queryName(queryName);
            newBuilder.boost(boost);
            newBuilder.setTestName(testName);
            return newBuilder;
        } else {
            return this;
        }
    }

    @Override
    protected boolean doEquals(ABTestQueryBuilder obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        EqualsBuilder equalsBuilder = new EqualsBuilder();
        equalsBuilder.append(queries, obj.queries);
        equalsBuilder.append(queryNames, obj.queryNames);
        equalsBuilder.append(testName, obj.testName);
        return equalsBuilder.isEquals();
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(queries, queryNames, testName);
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public void visit(QueryBuilderVisitor visitor) {
        visitor.accept(this);
        QueryBuilderVisitor subVisitor = visitor.getChildVisitor(Occur.MUST);
        for (QueryBuilder subQueryBuilder : queries) {
            subQueryBuilder.visit(subVisitor);
        }
    }

    private Collection<Query> toQueries(Collection<QueryBuilder> queryBuilders, QueryShardContext context) throws QueryShardException {
        List<Query> queries = queryBuilders.stream().map(qb -> {
            try {
                return qb.rewrite(context).toQuery(context);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).filter(Objects::nonNull).collect(Collectors.toList());
        return queries;
    }
}
