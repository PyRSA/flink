/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.planner.runtime.stream.table

import org.apache.flink.table.api._
import org.apache.flink.table.api.DataTypes.DECIMAL
import org.apache.flink.table.api.bridge.scala._
import org.apache.flink.table.connector.ChangelogMode
import org.apache.flink.table.legacy.api.Types
import org.apache.flink.table.planner.factories.TestValuesTableFactory
import org.apache.flink.table.planner.runtime.utils._
import org.apache.flink.table.planner.runtime.utils.JavaUserDefinedAggFunctions.{CountDistinct, DataViewTestAgg, WeightedAvg}
import org.apache.flink.table.planner.runtime.utils.StreamingWithStateTestBase.StateBackendMode
import org.apache.flink.table.planner.runtime.utils.TestData._
import org.apache.flink.table.planner.utils.CountMinMax
import org.apache.flink.testutils.junit.extensions.parameterized.ParameterizedTestExtension
import org.apache.flink.types.Row

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Percentage
import org.junit.jupiter.api.{BeforeEach, TestTemplate}
import org.junit.jupiter.api.extension.ExtendWith

import java.time.Duration

import scala.collection.JavaConversions._
import scala.collection.mutable

/** Tests of groupby (without window) aggregations */
@ExtendWith(Array(classOf[ParameterizedTestExtension]))
class AggregateITCase(mode: StateBackendMode) extends StreamingWithStateTestBase(mode) {

  @BeforeEach
  override def before(): Unit = {
    super.before()
    tEnv.getConfig.setIdleStateRetention(Duration.ofHours(1))
  }

  @TestTemplate
  def testDistinctUDAGG(): Unit = {
    val t = failingDataSource(tupleData5)
      .toTable(tEnv, 'a, 'b, 'c, 'd, 'e)
      .groupBy('e)
      .select('e, call(classOf[DataViewTestAgg], 'd, 'e).distinct())

    val sink = new TestingRetractSink()
    t.toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = mutable.MutableList("1,10", "2,21", "3,12")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testMaxAggRetractWithCondition(): Unit = {
    val data = new mutable.MutableList[(Int, Int)]
    data.+=((1, 10))
    data.+=((1, 10))
    data.+=((2, 5))
    data.+=((1, 10))

    // select id, price, count() as c, from table group by id, price
    val subQuery = failingDataSource(data)
      .toTable(tEnv, 'id, 'price)
      .groupBy('id, 'price)
      .aggregate('id.count().as("c"))
      .select('id, 'price, 'c)

    // select max(price) from subQuery where c > 0 and c < 3
    val topQuery = subQuery
      .where(and('c.isGreater(0), 'c.isLess(3)))
      .select('price)
      .aggregate('price.max().as("max_price"))
      .select('max_price)

    val sink = new TestingRetractSink()
    topQuery.toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()

    assertThat(sink.getRetractResults.sorted).isEqualTo(List("5"))
  }

  @TestTemplate
  def testMinAggRetractWithCondition(): Unit = {
    val data = new mutable.MutableList[(Int, Int)]
    data.+=((1, 5))
    data.+=((2, 6))
    data.+=((1, 5))

    // select id, price, count() as c, from table group by id, price
    val subQuery = failingDataSource(data)
      .toTable(tEnv, 'id, 'price)
      .groupBy('id, 'price)
      .aggregate('id.count().as("c"))
      .select('id, 'price, 'c)

    // select min(price) from subQuery where c < 2
    val topQuery = subQuery
      .where('c.isLess(2))
      .select('price)
      .aggregate('price.min().as("min_price"))
      .select('min_price)

    val sink = new TestingRetractSink()
    topQuery.toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()

    assertThat(sink.getRetractResults.sorted).isEqualTo(List("6"))
  }

  @TestTemplate
  def testDistinctUDAGGMixedWithNonDistinctUsage(): Unit = {
    val testAgg = new WeightedAvg
    val t = failingDataSource(tupleData5)
      .toTable(tEnv, 'a, 'b, 'c, 'd, 'e)
      .groupBy('e)
      .select('e, testAgg.distinct('a, 'a), testAgg('a, 'a))

    val sink = new TestingRetractSink()
    t.toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = mutable.MutableList("1,3,3", "2,3,4", "3,4,4")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testDistinctAggregate(): Unit = {
    val data = new mutable.MutableList[(Int, Int, String)]
    data.+=((1, 1, "A"))
    data.+=((2, 2, "B"))
    data.+=((2, 2, "B"))
    data.+=((4, 3, "C"))
    data.+=((5, 3, "C"))
    data.+=((4, 3, "C"))
    data.+=((7, 3, "B"))
    data.+=((1, 4, "A"))
    data.+=((9, 4, "D"))
    data.+=((4, 1, "A"))
    data.+=((3, 2, "B"))

    val testAgg = new WeightedAvg
    val t = failingDataSource(data)
      .toTable(tEnv, 'a, 'b, 'c)
      .groupBy('c)
      .select(
        'c,
        'a.count.distinct,
        'a.sum.distinct,
        testAgg.distinct('a, 'b),
        testAgg.distinct('b, 'a),
        testAgg('a, 'b))

    val sink = new TestingRetractSink()
    t.toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = mutable.MutableList("A,2,5,1,1,1", "B,3,12,4,2,3", "C,2,9,4,3,4", "D,1,9,9,4,9")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testDistinctAggregateMixedWithNonDistinct(): Unit = {
    val t = failingDataSource(tupleData5)
      .toTable(tEnv, 'a, 'b, 'c, 'd, 'e)
      .groupBy('e)
      .select('e, 'a.count.distinct, 'b.count)

    val sink = new TestingRetractSink()
    t.toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = mutable.MutableList("1,4,5", "2,4,7", "3,2,3")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

//  @TestTemplate
//  def testSimpleLogical(): Unit = {
//    val t = failingDataSource(smallTupleData3).toTable(tEnv, 'a, 'b, 'c)
//      .select('c.firstValue, 'c.lastValue, 'c.LISTAGG("#"))
//
//    val sink = new TestingRetractSink()
//    t.toRetractStream[Row].addSink(sink)
//    env.execute()
//
//    val expected = mutable.MutableList("Hi,Hello world,Hi#Hello#Hello world")
//    assertEquals(expected.sorted, sink.getRetractResults.sorted)
//  }

  @TestTemplate
  def testDistinct(): Unit = {
    val t = failingDataSource(tupleData3)
      .toTable(tEnv, 'a, 'b, 'c)
      .select('b, nullOf(Types.LONG))
      .distinct()

    val sink = new TestingRetractSink()
    t.toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = mutable.MutableList("1,null", "2,null", "3,null", "4,null", "5,null", "6,null")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testDistinctAfterAggregate(): Unit = {
    val t = failingDataSource(tupleData5)
      .toTable(tEnv, 'a, 'b, 'c, 'd, 'e)
      .groupBy('e)
      .select('e, 'a.count)
      .distinct()

    val sink = new TestingRetractSink()
    t.toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = mutable.MutableList("1,5", "2,7", "3,3")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testNonKeyedGroupAggregate(): Unit = {
    val t = failingDataSource(tupleData3)
      .toTable(tEnv, 'a, 'b, 'c)
      .select('a.sum, 'b.sum)

    val sink = new TestingRetractSink()
    t.toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()

    val expected = List("231,91")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testGroupAggregate(): Unit = {
    val t = failingDataSource(tupleData3)
      .toTable(tEnv, 'a, 'b, 'c)
      .groupBy('b)
      .select('b, 'a.sum)

    val sink = new TestingRetractSink()
    t.toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("1,1", "2,5", "3,15", "4,34", "5,65", "6,111")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testDoubleGroupAggregation(): Unit = {
    val t = failingDataSource(tupleData3)
      .toTable(tEnv, 'a, 'b, 'c)
      .groupBy('b)
      .select('a.count.as('cnt), 'b)
      .groupBy('cnt)
      .select('cnt, 'b.count.as('freq), 'b.min.as('min), 'b.max.as('max))

    val sink = new TestingRetractSink()
    t.toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("1,1,1,1", "2,1,2,2", "3,1,3,3", "4,1,4,4", "5,1,5,5", "6,1,6,6")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testDoubleGroupMaxMinAggregation(): Unit = {
    val t = failingDataSource(tupleData5)
      .toTable(tEnv, 'a, 'b, 'c, 'd, 'e)
      .groupBy('a, 'e)
      .select('a, 'e, 'b.max.as('f), 'b.min.as('g))
      .groupBy('a)
      .select('a, 'f.max, 'g.min)

    val sink = new TestingRetractSink()
    t.toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("1,1,1", "2,3,2", "3,6,4", "4,10,7", "5,15,11")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testGroupAggregateWithExpression(): Unit = {
    val t = failingDataSource(tupleData5)
      .toTable(tEnv, 'a, 'b, 'c, 'd, 'e)
      .groupBy('e, 'b % 3)
      .select(
        'c.min,
        'e,
        'a.avg,
        'd.count,
        'b.firstValue(),
        call("LAST_VALUE", col("c")),
        'd.listAgg("-"))

    val sink = new TestingRetractSink()
    t.toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = mutable.MutableList(
      s"0,1,1,1,1,0,Hallo",
      s"1,2,3,3,2,13,Hallo Welt-ABC-JKL",
      s"12,3,5,1,13,12,IJK",
      s"14,2,5,1,15,14,KLM",
      s"2,1,3,2,3,8,Hallo Welt wie-EFG",
      s"3,2,3,3,4,9,Hallo Welt wie gehts?-CDE-FGH",
      s"5,3,4,2,6,11,BCD-HIJ",
      s"7,1,4,2,8,10,DEF-GHI"
    )
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testCollect(): Unit = {
    val t = failingDataSource(tupleData3)
      .toTable(tEnv, 'a, 'b, 'c)
      .groupBy('b)
      .select('b, 'a.collect)

    val sink = new TestingRetractSink()
    t.toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()

    val expected = List(
      "1,{1=1}",
      "2,{2=1, 3=1}",
      "3,{4=1, 5=1, 6=1}",
      "4,{8=1, 9=1, 10=1, 7=1}",
      "5,{11=1, 12=1, 13=1, 14=1, 15=1}",
      "6,{16=1, 17=1, 18=1, 19=1, 20=1, 21=1}")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testGroupAggregateWithStateBackend(): Unit = {
    val data = new mutable.MutableList[(Int, Long, String)]
    data.+=((1, 1L, "A"))
    data.+=((2, 2L, "B"))
    data.+=((3, 2L, "B"))
    data.+=((4, 3L, "C"))
    data.+=((5, 3L, "C"))
    data.+=((6, 3L, "C"))
    data.+=((7, 4L, "B"))
    data.+=((8, 4L, "A"))
    data.+=((9, 4L, "D"))
    data.+=((10, 4L, "E"))
    data.+=((11, 5L, "A"))
    data.+=((12, 5L, "B"))

    val distinct = new CountDistinct
    val t = StreamingEnvUtil
      .fromCollection(env, data)
      .toTable(tEnv, 'a, 'b, 'c)
      .groupBy('b)
      .select('b, distinct('c), call(classOf[DataViewTestAgg], 'c, 'b))

    val sink = new TestingRetractSink()
    t.toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("1,1,2", "2,1,5", "3,1,10", "4,4,20", "5,2,12")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)

    // verify agg close is called
    assert(JavaUserDefinedAggFunctions.isCloseCalled)
  }

  @TestTemplate
  def testRemoveDuplicateRecordsWithUpsertSink(): Unit = {
    val data = new mutable.MutableList[(Int, Long, String)]
    data.+=((1, 1L, "A"))
    data.+=((2, 2L, "B"))
    data.+=((3, 2L, "B"))
    data.+=((4, 3L, "C"))
    data.+=((5, 3L, "C"))

    val t = StreamingEnvUtil
      .fromCollection(env, data)
      .toTable(tEnv, 'a, 'b, 'c)
      .groupBy('c)
      .select('c, 'b.max)

    TestSinkUtil.addValuesSink(
      tEnv,
      "testSink",
      List("c", "bMax"),
      List(DataTypes.STRING, DataTypes.BIGINT),
      ChangelogMode.upsert(),
      List("c"))

    t.executeInsert("testSink").await()

    val expected = List("+I[A, 1]", "+I[B, 2]", "+I[C, 3]")
    assertThat(
      TestValuesTableFactory
        .getResultsAsStrings("testSink")
        .sorted).isEqualTo(expected)
  }

  @TestTemplate
  def testNonGroupedAggregate(): Unit = {
    val testAgg = new CountMinMax
    val t = failingDataSource(tupleData3)
      .toTable(tEnv, 'a, 'b, 'c)
      .aggregate(testAgg('a))
      .select('f0, 'f1, 'f2)

    val sink = new TestingRetractSink()
    t.toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()

    val expected = List("21,1,21")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testAggregate(): Unit = {
    val testAgg = new CountMinMax
    val t = failingDataSource(tupleData3)
      .toTable(tEnv, 'a, 'b, 'c)
      .groupBy('b)
      .aggregate(testAgg('a))
      .select('b, 'f0, 'f1, 'f2)

    val sink = new TestingRetractSink()
    t.toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("1,1,1,1", "2,2,2,3", "3,3,4,6", "4,4,7,10", "5,5,11,15", "6,6,16,21")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testGroupAggregateWithDataView(): Unit = {
    val data = new mutable.MutableList[(Int, Long, String)]
    data.+=((1, 1L, "A"))
    data.+=((2, 2L, "B"))
    data.+=((3, 2L, "B"))
    data.+=((4, 3L, "C"))
    data.+=((5, 3L, "C"))
    data.+=((6, 3L, "C"))
    data.+=((7, 4L, "B"))
    data.+=((8, 4L, "A"))
    data.+=((9, 4L, "D"))
    data.+=((10, 4L, "E"))
    data.+=((11, 5L, "A"))
    data.+=((12, 5L, "B"))

    val distinct = new CountDistinct
    val t = failingDataSource(data)
      .toTable(tEnv, 'a, 'b, 'c)
      .groupBy('b)
      .select('b, distinct('c), call(classOf[DataViewTestAgg], 'c, 'b))

    val sink = new TestingRetractSink
    t.toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()

    val expected = List("1,1,2", "2,1,5", "3,1,10", "4,4,20", "5,2,12")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)

    // verify agg close is called
    assert(JavaUserDefinedAggFunctions.isCloseCalled)
  }

  @TestTemplate
  def testMaxRetractOptimize(): Unit = {
    val t = failingDataSource(tupleData3).toTable(tEnv, 'a, 'b, 'c)

    val results = t
      .groupBy('b, 'c)
      .select('b, 'c, 'a.max.as('a))
      .groupBy('b)
      .select('b, 'a.max)
      .toRetractStream[Row]

    val sink = new TestingRetractSink
    results.addSink(sink).setParallelism(1)
    env.execute()

    val expected = mutable.MutableList("1,1", "2,3", "3,6", "4,10", "5,15", "6,21")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testPrecisionForSumAggregationOnDecimal(): Unit = {
    val data = new mutable.MutableList[(Double, Double, Double, Double)]
    data.+=((1.03520274, 12345.035202748654, 12.345678901234567, 1.11111111))
    data.+=((0, 0, 0, 1.11111111))
    val t = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c, 'd)

    val results = t
      .select(
        'a.cast(DECIMAL(32, 8)).sum.as('a),
        'b.cast(DECIMAL(30, 20)).sum.as('b),
        'c.cast(DECIMAL(25, 20)).sum.as('c),
        'd.cast(DECIMAL(32, 8)).sum.as('d))
      .toRetractStream[Row]

    val sink = new TestingRetractSink
    results.addSink(sink).setParallelism(1)
    env.execute()

    // Use the result precision/scale calculated for sum and don't override with the one calculated
    // for plus(), which result in loosing a decimal digit.
    val expected = List("1.03520274,12345.03520274865300000000,12.34567890123456700000,2.22222222")
    assertThat(sink.getRetractResults).isEqualTo(expected)
  }

  @TestTemplate
  def testPrecisionForSum0AggregationOnDecimal(): Unit = {
    val data = new mutable.MutableList[(Double, Double, Double, Double)]
    data.+=((1.03520274, 12345.035202748654, 12.345678901234567, 1.11111111))
    data.+=((0, 0, 0, 1.11111111))
    val t = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c, 'd)

    val results = t
      .select(
        'a.cast(DECIMAL(32, 8)).sum0.as('a),
        'b.cast(DECIMAL(30, 20)).sum0.as('b),
        'c.cast(DECIMAL(25, 20)).sum0.as('c),
        'd.cast(DECIMAL(32, 8)).sum0.as('d))
      .toRetractStream[Row]

    val sink = new TestingRetractSink
    results.addSink(sink).setParallelism(1)
    env.execute()

    // Use the result precision/scale calculated for sum and don't override with the one calculated
    // for plus()/minus(), which result in loosing a decimal digit.
    val expected = List("1.03520274,12345.03520274865300000000,12.34567890123456700000,2.22222222")
    assertThat(sink.getRetractResults).isEqualTo(expected)
  }

  @TestTemplate
  def testPrecisionForAvgAggregationOnDecimal(): Unit = {
    val data = new mutable.MutableList[(Double, Double, Double, Double)]
    data.+=((1.03520274, 12345.035202748654, 12.345678901234567, 1.11111111))
    data.+=((0, 0, 0, 2.22222222))
    val t = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c, 'd)

    val results = t
      .select(
        'a.cast(DECIMAL(32, 8)).avg.as('a),
        'b.cast(DECIMAL(30, 20)).avg.as('b),
        'c.cast(DECIMAL(25, 20)).avg.as('c),
        'd.cast(DECIMAL(32, 8)).avg.as('d))
      .toRetractStream[Row]

    val sink = new TestingRetractSink
    results.addSink(sink).setParallelism(1)
    env.execute()

    // Use the result precision/scale calculated for AvgAggFunction's SumType and don't override
    // with the one calculated for plus()/minus(), which result in loosing a decimal digit.
    val expected = List("0.51760137,6172.51760137432650000000,6.17283945061728350000,1.66666667")
    assertThat(sink.getRetractResults).isEqualTo(expected)
  }

  @TestTemplate
  def testPercentile(): Unit = {
    val t = failingDataSource(tupleData5)
      .toTable(tEnv, 'a, 'b, 'c, 'd, 'e)
      .groupBy('e)
      .select(
        'e,
        'a.percentile(0.7).as('swo),
        'a.percentile(0.7, 'b).as('sw),
        'a.percentile(array(0.3, 0.1, 0.7)).as('mwo),
        'a.percentile(array(0.3, 0.1, 0.7), 'b).as('mw))
      .select('e, 'swo, 'sw, 'mwo.at(1), 'mwo.at(2), 'mwo.at(3), 'mw.at(1), 'mw.at(2), 'mw.at(3))

    val sink = new TestingRetractSink
    t.toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()

    val expected = List(
      List(4.0, 5.0, 2.4, 1.4, 4.0, 4.0, 2.2, 5.0),
      List(4.2, 5.0, 3.0, 2.6, 4.2, 4.0, 3.0, 5.0),
      List(5.0, 5.0, 4.2, 3.4, 5.0, 5.0, 3.0, 5.0))
    val ERROR_RATE = Percentage.withPercentage(1e-6)

    val result = sink.getRetractResults.sorted
    for (i <- result.indices) {
      val actual = result(i).split(",")
      assertThat(actual(0).toInt).isEqualTo(i + 1)
      for (j <- expected(i).indices) {
        assertThat(actual(j + 1).toDouble).isCloseTo(expected(i)(j), ERROR_RATE)
      }
    }
  }
}
