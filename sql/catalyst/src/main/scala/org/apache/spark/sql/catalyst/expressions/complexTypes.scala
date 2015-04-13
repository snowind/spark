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

package org.apache.spark.sql.catalyst.expressions

import scala.collection.Map

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.analysis.Resolver
import org.apache.spark.sql.types._

/**
 * Returns the item at `ordinal` in the Array `child` or the Key `ordinal` in Map `child`.
 */
case class GetItem(child: Expression, ordinal: Expression) extends Expression {
  type EvaluatedType = Any

  val children: Seq[Expression] = child :: ordinal :: Nil
  /** `Null` is returned for invalid ordinals. */
  override def nullable: Boolean = true
  override def foldable: Boolean = child.foldable && ordinal.foldable

  override def dataType: DataType = child.dataType match {
    case ArrayType(dt, _) => dt
    case MapType(_, vt, _) => vt
  }
  override lazy val resolved =
    childrenResolved &&
    (child.dataType.isInstanceOf[ArrayType] || child.dataType.isInstanceOf[MapType])

  override def toString: String = s"$child[$ordinal]"

  override def eval(input: Row): Any = {
    val value = child.eval(input)
    if (value == null) {
      null
    } else {
      val key = ordinal.eval(input)
      if (key == null) {
        null
      } else {
        if (child.dataType.isInstanceOf[ArrayType]) {
          // TODO: consider using Array[_] for ArrayType child to avoid
          // boxing of primitives
          val baseValue = value.asInstanceOf[Seq[_]]
          val o = key.asInstanceOf[Int]
          if (o >= baseValue.size || o < 0) {
            null
          } else {
            baseValue(o)
          }
        } else {
          val baseValue = value.asInstanceOf[Map[Any, _]]
          baseValue.get(key).orNull
        }
      }
    }
  }
}


trait GetField extends UnaryExpression {
  self: Product =>

  type EvaluatedType = Any
  override def foldable: Boolean = child.foldable
  override def toString: String = s"$child.${field.name}"

  def field: StructField
}

object GetField {
  /**
   * Returns the resolved `GetField`, and report error if no desired field or over one
   * desired fields are found.
   */
  def apply(
      expr: Expression,
      fieldName: String,
      resolver: Resolver): GetField = {
    def findField(fields: Array[StructField]): Int = {
      val checkField = (f: StructField) => resolver(f.name, fieldName)
      val ordinal = fields.indexWhere(checkField)
      if (ordinal == -1) {
        throw new AnalysisException(
          s"No such struct field $fieldName in ${fields.map(_.name).mkString(", ")}")
      } else if (fields.indexWhere(checkField, ordinal + 1) != -1) {
        throw new AnalysisException(
          s"Ambiguous reference to fields ${fields.filter(checkField).mkString(", ")}")
      } else {
        ordinal
      }
    }
    expr.dataType match {
      case StructType(fields) =>
        val ordinal = findField(fields)
        StructGetField(expr, fields(ordinal), ordinal)
      case ArrayType(StructType(fields), containsNull) =>
        val ordinal = findField(fields)
        ArrayGetField(expr, fields(ordinal), ordinal, containsNull)
      case otherType =>
        throw new AnalysisException(s"GetField is not valid on fields of type $otherType")
    }
  }
}

/**
 * Returns the value of fields in the Struct `child`.
 */
case class StructGetField(child: Expression, field: StructField, ordinal: Int) extends GetField {

  override def dataType: DataType = field.dataType
  override def nullable: Boolean = child.nullable || field.nullable

  override def eval(input: Row): Any = {
    val baseValue = child.eval(input).asInstanceOf[Row]
    if (baseValue == null) null else baseValue(ordinal)
  }
}

/**
 * Returns the array of value of fields in the Array of Struct `child`.
 */
case class ArrayGetField(child: Expression, field: StructField, ordinal: Int, containsNull: Boolean)
  extends GetField {

  override def dataType: DataType = ArrayType(field.dataType, containsNull)
  override def nullable: Boolean = child.nullable

  override def eval(input: Row): Any = {
    val baseValue = child.eval(input).asInstanceOf[Seq[Row]]
    if (baseValue == null) null else {
      baseValue.map { row =>
        if (row == null) null else row(ordinal)
      }
    }
  }
}

/**
 * Returns an Array containing the evaluation of all children expressions.
 */
case class CreateArray(children: Seq[Expression]) extends Expression {
  override type EvaluatedType = Any
  
  override def foldable: Boolean = children.forall(_.foldable)
  
  lazy val childTypes = children.map(_.dataType).distinct

  override lazy val resolved =
    childrenResolved && childTypes.size <= 1

  override def dataType: DataType = {
    assert(resolved, s"Invalid dataType of mixed ArrayType ${childTypes.mkString(",")}")
    ArrayType(
      childTypes.headOption.getOrElse(NullType),
      containsNull = children.exists(_.nullable))
  }

  override def nullable: Boolean = false

  override def eval(input: Row): Any = {
    children.map(_.eval(input))
  }

  override def toString: String = s"Array(${children.mkString(",")})"
}

/**
 * Returns a Row containing the evaluation of all children expressions.
 * TODO: [[CreateStruct]] does not support codegen.
 */
case class CreateStruct(children: Seq[NamedExpression]) extends Expression {
  override type EvaluatedType = Row

  override def foldable: Boolean = children.forall(_.foldable)

  override lazy val resolved: Boolean = childrenResolved

  override lazy val dataType: StructType = {
    assert(resolved,
      s"CreateStruct contains unresolvable children: ${children.filterNot(_.resolved)}.")
    val fields = children.map { child =>
      StructField(child.name, child.dataType, child.nullable, child.metadata)
    }
    StructType(fields)
  }

  override def nullable: Boolean = false

  override def eval(input: Row): EvaluatedType = {
    Row(children.map(_.eval(input)): _*)
  }
}
