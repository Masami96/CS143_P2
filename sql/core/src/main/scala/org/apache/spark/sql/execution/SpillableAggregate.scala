/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import org.apache.spark.sql.catalyst.errors._
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.physical.{ClusteredDistribution, AllTuples, UnspecifiedDistribution}
import org.apache.spark.util.collection.SizeTrackingAppendOnlyMap

case class SpillableAggregate(
                               partial: Boolean,
                               groupingExpressions: Seq[Expression],
                               aggregateExpressions: Seq[NamedExpression],
                               child: SparkPlan) extends UnaryNode {

  override def requiredChildDistribution =
    if (partial) {
      UnspecifiedDistribution :: Nil
    } else {
      if (groupingExpressions == Nil) {
        AllTuples :: Nil
      } else {
        ClusteredDistribution(groupingExpressions) :: Nil
      }
    }

  override def output = aggregateExpressions.map(_.toAttribute)

  /**
    * An aggregate that needs to be computed for each row in a group.
    *
    * @param unbound         Unbound version of this aggregate, used for result substitution.
    * @param aggregate       A bound copy of this aggregate used to create a new aggregation buffer.
    * @param resultAttribute An attribute used to refer to the result of this aggregate in the final
    *                        output.
    */
  case class ComputedAggregate(
                                unbound: AggregateExpression,
                                aggregate: AggregateExpression,
                                resultAttribute: AttributeReference)

  /** A list of aggregates that need to be computed for each group. */
  private[this] val computedAggregates = aggregateExpressions.flatMap { agg =>
    agg.collect {
      case a: AggregateExpression =>
        ComputedAggregate(
          a,
          BindReferences.bindReference(a, child.output), // change childOutput to its value due to private val
          AttributeReference(s"aggResult:$a", a.dataType, a.nullable)())
    }
  }
  // import the above computedAggregates val from Aggregate.scala

  /** Physical aggregator generated from a logical expression.  */
  private[this] val aggregator: ComputedAggregate = computedAggregates(0) // done

  /** Schema of the aggregate.  */
  private[this] val aggregatorSchema: AttributeReference = aggregator.resultAttribute // done

  /** Creates a new aggregator instance.  */
  private[this] def newAggregatorInstance(): AggregateFunction = aggregator.aggregate.newInstance() // done

  /** Named attributes used to substitute grouping attributes in the final result. */
  private[this] val namedGroups = groupingExpressions.map {
    case ne: NamedExpression => ne -> ne.toAttribute
    case e => e -> Alias(e, s"groupingExpr:$e")().toAttribute
  }

  /**
    * A map of substitutions that are used to insert the aggregate expressions and grouping
    * expression into the final result expression.
    */
  protected val resultMap =
    (Seq(aggregator.unbound -> aggregator.resultAttribute) ++ namedGroups).toMap

  /**
    * Substituted version of aggregateExpressions expressions which are used to compute final
    * output rows given a group and the result of all aggregate computations.
    */
  private[this] val resultExpression = aggregateExpressions.map(agg => agg.transform {
    case e: Expression if resultMap.contains(e) => resultMap(e)
  }
  )

  override def execute() = attachTree(this, "execute") {
    child.execute().mapPartitions(iter => generateIterator(iter))
  }

  /**
    * This method takes an iterator as an input. The iterator is drained either by aggregating
    * values or by spilling to disk. Spilled partitions are successively aggregated one by one
    * until no data is left.
    *
    * @param input      the input iterator
    * @param memorySize the memory size limit for this aggregate
    * @return the result of applying the projection
    */
  def generateIterator(input: Iterator[Row], memorySize: Long = 64 * 1024 * 1024, numPartitions: Int = 64): Iterator[Row] = {
    val groupingProjection = CS143Utils.getNewProjection(groupingExpressions, child.output)
    var currentAggregationTable = new SizeTrackingAppendOnlyMap[Row, AggregateFunction]
    var data = input

    def initSpills(): Array[DiskPartition] = {
      /* done */
      val p_Array = new Array[DiskPartition](numPartitions)
      val blockSize: Int = 0

      for (i <- 0 to numPartitions - 1) {
        p_Array(i) = new DiskPartition(i.toString(), blockSize)
      }

      p_Array
    }

    val spills = initSpills()

    new Iterator[Row] {
      /**
        * Global object wrapping the spills into a DiskHashedRelation. This variable is
        * set only when we are sure that the input iterator has been completely drained.
        *
        * @return
        */
      var hashedSpills: Iterator[DiskPartition] = spills.iterator // remove Option to assign to spills.iterator
      var diskHashedRelation: Option[DiskHashedRelation] = None
      var aggregateResult: Iterator[Row] = aggregate()

      def hasNext() = {
        /* done */

        if (aggregateResult.hasNext == true || fetchSpill() == true) {
          true
        }
        else {
          false
        }

      }

      def next() = {
        /* done */
        aggregateResult.next
      }

      /**
        * This method load the aggregation hash table by draining the data iterator
        *
        * @return
        */
      private def aggregate(): Iterator[Row] = {
        /* I done */

        var cur_row: Row = null
        while (data.hasNext) {
          cur_row = data.next
          val cur_group = groupingProjection(cur_row)
          var cur_instance = currentAggregationTable.apply(cur_group)


          if (cur_instance == null) {
            if (CS143Utils.maybeSpill(currentAggregationTable, memorySize) == true) {
              spillRecord(cur_group, cur_row)
            }
            else {
              cur_instance = newAggregatorInstance()
              currentAggregationTable.update(cur_group.copy(), cur_instance)
            }
          }

          if (cur_instance != null) {
            cur_instance.update(cur_row)
          }
        }

        val len = spills.length
        for (i <- 0 to len - 1){
          spills(i).closeInput()
        }

        AggregateIteratorGenerator(resultExpression, Seq(aggregatorSchema) ++ namedGroups.map(_._2))(currentAggregationTable.iterator)

      }

      /**
        * Spill input rows to the proper partition using hashing
        *
        * @return
        */
      private def spillRecord(group: Row, row: Row) = {
        /* done */
        spills(group.hashCode() % numPartitions).insert(row)
      }

      /**
        * This method fetches the next records to aggregate from spilled partitions or returns false if we
        * have no spills left.
        *
        * @return
        */
      private def fetchSpill(): Boolean = {
        /* done */
        currentAggregationTable = new SizeTrackingAppendOnlyMap[Row, AggregateFunction]
        var next_partition: DiskPartition = null

        while (hashedSpills.hasNext) {
          if (data.hasNext == false) {
            next_partition = hashedSpills.next
            data = next_partition.getData()
          }
          if (data.hasNext) {
            aggregateResult = aggregate()
            return true
          }
        }
        false
      }
    }
  }
}
