/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.pql.parsers;

import java.util.Collections;
import java.util.List;
import org.apache.pinot.common.request.AggregationInfo;
import org.apache.pinot.common.request.BrokerRequest;
import org.apache.pinot.common.request.FilterOperator;
import org.apache.pinot.common.request.GroupBy;
import org.apache.pinot.common.request.transform.TransformExpressionTree;
import org.apache.pinot.pql.parsers.pql2.ast.TopAstNode;
import org.testng.Assert;
import org.testng.annotations.Test;


/**
 * Some tests for the PQL 2 compiler.
 */
public class Pql2CompilerTest {
  private static final Pql2Compiler COMPILER = new Pql2Compiler();

  @Test
  public void testQuotedStrings() {
    BrokerRequest brokerRequest =
        COMPILER.compileToBrokerRequest("select * from vegetables where origin = 'Martha''s Vineyard'");
    Assert.assertEquals(brokerRequest.getFilterQuery().getValue().get(0), "Martha's Vineyard");

    brokerRequest = COMPILER.compileToBrokerRequest("select * from vegetables where origin = 'Martha\"\"s Vineyard'");
    Assert.assertEquals(brokerRequest.getFilterQuery().getValue().get(0), "Martha\"\"s Vineyard");

    brokerRequest = COMPILER.compileToBrokerRequest("select * from vegetables where origin = \"Martha\"\"s Vineyard\"");
    Assert.assertEquals(brokerRequest.getFilterQuery().getValue().get(0), "Martha\"s Vineyard");

    brokerRequest = COMPILER.compileToBrokerRequest("select * from vegetables where origin = \"Martha''s Vineyard\"");
    Assert.assertEquals(brokerRequest.getFilterQuery().getValue().get(0), "Martha''s Vineyard");
  }

  @Test
  public void testDuplicateClauses() {
    assertCompilationFails("select top 5 count(*) from a top 8");
    assertCompilationFails("select count(*) from a where a = 1 limit 5 where b = 2");
    assertCompilationFails("select count(*) from a group by b limit 5 group by b");
    assertCompilationFails("select count(*) from a having sum(a) = 8 limit 5 having sum(a) = 9");
    assertCompilationFails("select count(*) from a order by b limit 5 order by c");
    assertCompilationFails("select count(*) from a limit 5 limit 5");
  }

  @Test
  public void testTopZero() {
    testTopZeroFor("select count(*) from someTable where c = 5 group by X top 0", TopAstNode.DEFAULT_TOP_N, false);
    testTopZeroFor("select count(*) from someTable where c = 5 group by X top 1", 1, false);
    testTopZeroFor("select count(*) from someTable where c = 5 group by X top -1", TopAstNode.DEFAULT_TOP_N, true);
  }

  private void assertCompilationFails(String query) {
    try {
      COMPILER.compileToBrokerRequest(query);
    } catch (Pql2CompilationException e) {
      // Expected
      return;
    }

    Assert.fail("Query " + query + " compiled successfully but was expected to fail compilation");
  }

  private void testTopZeroFor(String s, final int expectedTopN, boolean parseException) {
    BrokerRequest req;
    try {
      req = COMPILER.compileToBrokerRequest(s);
    } catch (Pql2CompilationException e) {
      if (parseException) {
        return;
      }
      throw e;
    }
    Assert.assertTrue(req.isSetGroupBy());
    GroupBy groupBy = req.getGroupBy();
    Assert.assertTrue(groupBy.isSetTopN());
    Assert.assertEquals(expectedTopN, groupBy.getTopN());
  }

  @Test
  public void testRejectInvalidLexerToken() {
    assertCompilationFails("select foo from bar where baz ?= 2");
    assertCompilationFails("select foo from bar where baz =! 2");
  }

  @Test
  public void testRejectInvalidParses() {
    assertCompilationFails("select foo from bar where baz < > 2");
    assertCompilationFails("select foo from bar where baz ! = 2");
  }

  @Test
  public void testParseExceptionHasCharacterPosition() {
    final String query = "select foo from bar where baz ? 2";

    try {
      COMPILER.compileToBrokerRequest(query);
    } catch (Pql2CompilationException e) {
      // Expected
      Assert.assertTrue(e.getMessage().startsWith("1:30: "),
          "Compilation exception should contain line and character for error message. Error message is " + e
              .getMessage());
      return;
    }

    Assert.fail("Query " + query + " compiled successfully but was expected to fail compilation");
  }

  @Test
  public void testCStyleInequalityOperator() {

    BrokerRequest brokerRequest =
        COMPILER.compileToBrokerRequest("select * from vegetables where name != 'Brussels sprouts'");
    Assert.assertEquals(brokerRequest.getFilterQuery().getOperator(), FilterOperator.NOT);
  }

  @Test
  public void testCompilationWithHaving() {
    BrokerRequest brokerRequest = COMPILER
        .compileToBrokerRequest("select avg(age) as avg_age from person group by address_city having avg(age)=20");
    Assert.assertEquals(brokerRequest.getHavingFilterQuery().getOperator(), FilterOperator.EQUALITY);
    Assert.assertEquals(brokerRequest.getHavingFilterQuery().getAggregationInfo().getAggregationType(), "avg");
    Assert.assertEquals(brokerRequest.getHavingFilterQuery().getAggregationInfo().getAggregationParams().get("column"),
        "age");
    Assert.assertEquals(brokerRequest.getHavingFilterQuery().getValue().get(0), "20");
    brokerRequest = COMPILER.compileToBrokerRequest(
        "select count(*) as count from sell group by price having count(*) > 100 AND count(*)<200");
    Assert.assertEquals(brokerRequest.getHavingFilterSubQueryMap().getFilterQueryMap().size(), 3);
    Assert.assertEquals(brokerRequest.getHavingFilterQuery().getOperator(), FilterOperator.AND);
    brokerRequest = COMPILER.compileToBrokerRequest(
        "select count(*) as count, avg(price) as avgprice from sell having count(*) > 0 OR (avg(price) < 45 AND count(*) > 22)");
    Assert.assertEquals(brokerRequest.getHavingFilterSubQueryMap().getFilterQueryMap().size(), 5);

    brokerRequest = COMPILER.compileToBrokerRequest(
        "SELECT count(*) FROM mytable WHERE DaysSinceEpoch >= 16312 group by Carrier having count(*) in (375,5005,1099)");
    Assert.assertEquals(brokerRequest.getHavingFilterSubQueryMap().getFilterQueryMap().size(), 1);
    Assert.assertEquals(brokerRequest.getHavingFilterQuery().getOperator(), FilterOperator.IN);
    Assert.assertEquals(brokerRequest.getHavingFilterQuery().getValue().size(), 1);
    Assert.assertEquals(brokerRequest.getHavingFilterQuery().getValue().get(0).contains("375"), true);
    Assert.assertEquals(brokerRequest.getHavingFilterQuery().getValue().get(0).contains("5005"), true);
    Assert.assertEquals(brokerRequest.getHavingFilterQuery().getValue().get(0).contains("1099"), true);
    brokerRequest = COMPILER.compileToBrokerRequest(
        "SELECT count(*) FROM mytable WHERE DaysSinceEpoch >= 16312 group by Carrier having count(*) not in (375,5005,1099)");
    Assert.assertEquals(brokerRequest.getHavingFilterSubQueryMap().getFilterQueryMap().size(), 1);
    Assert.assertEquals(brokerRequest.getHavingFilterQuery().getOperator(), FilterOperator.NOT_IN);
  }

  @Test
  public void testQueryOptions() {
    BrokerRequest brokerRequest =
        COMPILER.compileToBrokerRequest("select * from vegetables where name != 'Brussels sprouts'");
    Assert.assertEquals(brokerRequest.getQueryOptionsSize(), 0);
    Assert.assertNull(brokerRequest.getQueryOptions());

    brokerRequest = COMPILER
        .compileToBrokerRequest("select * from vegetables where name != 'Brussels sprouts' OPTION (delicious=yes)");
    Assert.assertEquals(brokerRequest.getQueryOptionsSize(), 1);
    Assert.assertTrue(brokerRequest.getQueryOptions().containsKey("delicious"));
    Assert.assertEquals(brokerRequest.getQueryOptions().get("delicious"), "yes");

    brokerRequest = COMPILER.compileToBrokerRequest(
        "select * from vegetables where name != 'Brussels sprouts' OPTION (delicious=yes, foo=1234, bar='potato')");
    Assert.assertEquals(brokerRequest.getQueryOptionsSize(), 3);
    Assert.assertTrue(brokerRequest.getQueryOptions().containsKey("delicious"));
    Assert.assertEquals(brokerRequest.getQueryOptions().get("delicious"), "yes");
    Assert.assertEquals(brokerRequest.getQueryOptions().get("foo"), "1234");
    Assert.assertEquals(brokerRequest.getQueryOptions().get("bar"), "potato");

    brokerRequest = COMPILER.compileToBrokerRequest(
        "select * from vegetables where name != 'Brussels sprouts' OPTION (delicious=yes) option(foo=1234) option(bar='potato')");
    Assert.assertEquals(brokerRequest.getQueryOptionsSize(), 3);
    Assert.assertTrue(brokerRequest.getQueryOptions().containsKey("delicious"));
    Assert.assertEquals(brokerRequest.getQueryOptions().get("delicious"), "yes");
    Assert.assertEquals(brokerRequest.getQueryOptions().get("foo"), "1234");
    Assert.assertEquals(brokerRequest.getQueryOptions().get("bar"), "potato");
  }

  @Test
  public void testIdentifierQuoteCharacter() {
    TransformExpressionTree expTree = COMPILER.compileToExpressionTree("`a.b.c`");
    Assert.assertEquals(expTree.getExpressionType(), TransformExpressionTree.ExpressionType.IDENTIFIER);
    Assert.assertEquals(expTree.getValue(), "a.b.c");

    BrokerRequest brokerRequest = COMPILER.compileToBrokerRequest(
        "select avg(`attributes.age`) as `avg_age` from `person` group by `attributes.address_city` having avg(`attributes.age`)=20");

    Assert.assertEquals(brokerRequest.getAggregationsInfo().get(0).getAggregationParams().get("column"),
        "attributes.age");
    Assert.assertEquals(brokerRequest.getGroupBy().getExpressions(),
        Collections.singletonList("attributes.address_city"));
    Assert.assertEquals(brokerRequest.getHavingFilterQuery().getAggregationInfo().getAggregationParams().get("column"),
        "attributes.age");
  }

  @Test
  public void testStringLiteral() {
    // Do not allow string literal column in selection query
    assertCompilationFails("SELECT 'foo' FROM table");

    // Allow string literal column in aggregation and group-by query
    BrokerRequest brokerRequest =
        COMPILER.compileToBrokerRequest("SELECT SUM('foo'), MAX(\"bar\") FROM table GROUP BY 'foo', \"bar\"");
    List<AggregationInfo> aggregationInfos = brokerRequest.getAggregationsInfo();
    Assert.assertEquals(aggregationInfos.size(), 2);
    Assert.assertEquals(aggregationInfos.get(0).getAggregationParams().get("column"), "foo");
    Assert.assertEquals(aggregationInfos.get(1).getAggregationParams().get("column"), "bar");
    List<String> expressions = brokerRequest.getGroupBy().getExpressions();
    Assert.assertEquals(expressions.size(), 2);
    Assert.assertEquals(expressions.get(0), "foo");
    Assert.assertEquals(expressions.get(1), "bar");

    // For UDF, string literal won't be treated as column but as LITERAL
    brokerRequest =
        COMPILER.compileToBrokerRequest("SELECT SUM(ADD(foo, 'bar')) FROM table GROUP BY SUB(\"foo\", bar)");
    aggregationInfos = brokerRequest.getAggregationsInfo();
    Assert.assertEquals(aggregationInfos.size(), 1);
    Assert.assertEquals(aggregationInfos.get(0).getAggregationParams().get("column"), "add(foo,'bar')");
    expressions = brokerRequest.getGroupBy().getExpressions();
    Assert.assertEquals(expressions.size(), 1);
    Assert.assertEquals(expressions.get(0), "sub('foo',bar)");
  }
}
