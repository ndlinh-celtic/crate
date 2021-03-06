/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.analyze;

import com.google.common.collect.ImmutableMap;
import io.crate.analyze.expressions.ExpressionAnalysisContext;
import io.crate.analyze.expressions.ExpressionAnalyzer;
import io.crate.analyze.relations.AnalyzedRelation;
import io.crate.analyze.relations.FullQualifedNameFieldProvider;
import io.crate.analyze.relations.AnalyzedRelationVisitor;
import io.crate.metadata.MetaDataModule;
import io.crate.metadata.Path;
import io.crate.metadata.information.MetaDataInformationModule;
import io.crate.operation.operator.OperatorModule;
import io.crate.operation.predicate.PredicateModule;
import io.crate.operation.scalar.ScalarFunctionModule;
import io.crate.planner.symbol.Field;
import io.crate.planner.symbol.Symbol;
import io.crate.sql.parser.SqlParser;
import io.crate.sql.tree.QualifiedName;
import io.crate.test.integration.CrateUnitTest;
import io.crate.testing.MockedClusterServiceModule;
import io.crate.types.DataTypes;
import org.elasticsearch.common.inject.Binder;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.inject.ModulesBuilder;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.crate.testing.TestingHelpers.assertLiteralSymbol;

public class ReferenceToTrueVisitorTest extends CrateUnitTest {

    private ReferenceToTrueVisitor visitor;
    private ExpressionAnalyzer expressionAnalyzer;
    private ExpressionAnalysisContext expressionAnalysisContext;
    private ThreadPool threadPool;

    @Before
    public void prepare() throws Exception {
        threadPool = new ThreadPool("testing");
        Injector injector = new ModulesBuilder()
            .add(new Module() {
                @Override
                public void configure(Binder binder) {
                    binder.bind(ThreadPool.class).toInstance(threadPool);
                }
            })
            .add(new MockedClusterServiceModule())
            .add(new MetaDataModule())
            .add(new OperatorModule())
            .add(new MetaDataInformationModule())
            .add(new ScalarFunctionModule())
            .add(new PredicateModule()).createInjector();
        visitor = new ReferenceToTrueVisitor();
        expressionAnalyzer = new ExpressionAnalyzer(
                injector.getInstance(AnalysisMetaData.class),
                new ParameterContext(new Object[0], new Object[0][], null),
                new FullQualifedNameFieldProvider(
                        ImmutableMap.<QualifiedName, AnalyzedRelation>of(new QualifiedName("dummy"), new DummyRelation()))
        );
        expressionAnalysisContext = new ExpressionAnalysisContext();
    }

    @After
    public void after() throws Exception {
        threadPool.shutdown();
        threadPool.awaitTermination(1, TimeUnit.SECONDS);
    }

    private Symbol convert(Symbol symbol) {
        return expressionAnalyzer.normalize(visitor.process(symbol, null));
    }

    /**
     * relation that will return a Reference with Doc granularity / String type for all columns
     */
    private static class DummyRelation implements AnalyzedRelation {

        @Override
        public <C, R> R accept(AnalyzedRelationVisitor<C, R> visitor, C context) {
            return null;
        }

        @Override
        public Field getField(Path path) {
            return new Field(this, path, DataTypes.STRING);
        }

        @Override
        public Field getWritableField(Path path) throws UnsupportedOperationException {
            return null;
        }

        @Override
        public List<Field> fields() {
            return null;
        }
    }

    public Symbol fromSQL(String expression) {
        return expressionAnalyzer.convert(SqlParser.createExpression(expression), expressionAnalysisContext);
    }

    @Test
    public void testFalseAndMatchFunction() throws Exception {
        Symbol symbol = convert(fromSQL("false and match (table_name, 'jalla')"));
        assertLiteralSymbol(symbol, false);
    }

    @Test
    public void testTrueAndMatchFunction() throws Exception {
        Symbol symbol = convert(fromSQL("true and match (table_name, 'jalla')"));
        assertLiteralSymbol(symbol, true);
    }

    @Test
    public void testComplexNestedDifferentMethods() throws Exception {
        Symbol symbol = convert(fromSQL(
            "number_of_shards = 1 or (number_of_replicas = 3 and schema_name = 'sys') " +
                "or not (number_of_shards = 2) and substr(table_name, 1, 1) = '1'"));
        assertLiteralSymbol(symbol, true);
    }

    @Test
    public void testIsNull() throws Exception {
        Symbol symbol = convert(fromSQL("clustered_by is null"));
        assertLiteralSymbol(symbol, true);
    }

    @Test
    public void testNot_NullAndSubstr() throws Exception {
        Symbol symbol = convert(fromSQL("not (null and substr(table_name, 1, 1) = '1')"));
        assertLiteralSymbol(symbol, true);
    }

    @Test
    public void testNot_FalseAndSubstr() throws Exception {
        Symbol symbol = convert(fromSQL("not (false and substr(table_name, 1, 1) = '1')"));
        assertLiteralSymbol(symbol, true);
    }

    @Test
    public void testNotPredicate() throws Exception {
        Symbol symbol = convert(fromSQL(("not (clustered_by = 'foo')")));
        assertLiteralSymbol(symbol, true);
    }

    @Test
    public void testComplexNestedDifferentMethodsEvaluatesToFalse() throws Exception {
        Symbol symbol = convert(fromSQL(
            "(number_of_shards = 1 or number_of_replicas = 3 and schema_name = 'sys' " +
                "or not (number_of_shards = 2)) and substr(table_name, 1, 1) = '1' and false"));
        assertLiteralSymbol(symbol, false);
    }

    @Test
    public void testNullAndMatchFunction() throws Exception {
        Symbol symbol = convert(fromSQL("null and match (table_name, 'jalla')"));
        assertLiteralSymbol(symbol, null, DataTypes.BOOLEAN);
    }

}
