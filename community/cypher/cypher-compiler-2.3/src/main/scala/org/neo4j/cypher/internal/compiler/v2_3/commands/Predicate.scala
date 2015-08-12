/*
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v2_3.commands

import org.neo4j.cypher.internal.compiler.v2_3._
import org.neo4j.cypher.internal.compiler.v2_3.commands.expressions.{Identifier, Expression, Literal, Property}
import org.neo4j.cypher.internal.compiler.v2_3.commands.values.KeyToken
import org.neo4j.cypher.internal.compiler.v2_3.executionplan.{Effects, ReadsLabel}
import org.neo4j.cypher.internal.compiler.v2_3.helpers.{NonEmptyList, CastSupport, CollectionSupport, IsCollection}
import org.neo4j.cypher.internal.compiler.v2_3.parser.ParsedLikePattern
import org.neo4j.cypher.internal.compiler.v2_3.pipes.QueryState
import org.neo4j.cypher.internal.compiler.v2_3.symbols._
import org.neo4j.graphdb._

import scala.util.{Failure, Success, Try}

abstract class Predicate extends Expression {
  def apply(ctx: ExecutionContext)(implicit state: QueryState) = isMatch(ctx).orNull
  def isTrue(m: ExecutionContext)(implicit state: QueryState): Boolean = isMatch(m).getOrElse(false)
  def andWith(other: Predicate): Predicate = Ands(this, other)
  def isMatch(m: ExecutionContext)(implicit state: QueryState): Option[Boolean]

  // This is the un-dividable list of predicates. They can all be ANDed
  // together
  def atoms: Seq[Predicate] = Seq(this)
  def containsIsNull: Boolean
  protected def calculateType(symbols: SymbolTable) = CTBoolean

  def andWith(preds: Predicate*): Predicate =
    if (preds.isEmpty) this else preds.fold(this)(_ andWith _)
}

object Predicate {
  def fromSeq(in: Seq[Predicate]) = in.reduceOption(_ andWith _).getOrElse(True())
}

object And {
  def apply(a: Predicate, b: Predicate) = (a, b) match {
    case (True(), other) => other
    case (other, True()) => other
    case (_, _)          => new And(a, b)
  }
}

object Ands {
  def apply(a: Predicate, b: Predicate) = (a, b) match {
    case (True(), other) => other
    case (other, True()) => other
    case (_, _)          => new Ands(List(a, b))
  }
}

case class Ands(predicates: List[Predicate]) extends CompositeBooleanPredicate {
  def shouldExitWhen = false
  override def andWith(other: Predicate): Predicate = Ands(predicates :+ other)
  def rewrite(f: (Expression) => Expression): Expression = f(Ands(predicates.map(_.rewriteAsPredicate(f))))
}

case class AndedPropertyComparablePredicates(ident: Identifier, prop: Property, comparables: NonEmptyList[ComparablePredicate]) extends Predicate {

  def symbolTableDependencies: Set[String] = comparables.foldLeft(prop.symbolTableDependencies) {
    case (acc, predicate) => acc ++ predicate.symbolTableDependencies
  }

  def containsIsNull: Boolean = comparables.exists(_.containsIsNull)

  def isMatch(m: ExecutionContext)(implicit state: QueryState): Option[Boolean] = {
    var result: Option[Option[Boolean]] = None
    val iter = comparables.toIterable.iterator
    while (iter.nonEmpty) {
      val p = iter.next()
      val r = p.isMatch(m)

      if(r.nonEmpty && !r.get)
        return r

      if(result.isEmpty)
        result = Some(r)
      else {
        val stored = result.get
        if (stored.nonEmpty && stored.get && r.isEmpty)
          result = Some(None)
      }
    }

    result.get
  }

  def arguments: Seq[Expression] = comparables.toSeq

  // some rewriters change the type of this, and we can't allow that
  private def rewriteIdentifierIfNotTypeChanged(f: (Expression) => Expression) =
    ident.rewrite(f) match {
      case i: Identifier => i
      case _ => ident
    }

  def rewrite(f: (Expression) => Expression): Expression =
    f(AndedPropertyComparablePredicates(rewriteIdentifierIfNotTypeChanged(f),
                                        prop.rewrite(f).asInstanceOf[Property],
                                        comparables.map(_.rewriteAsPredicate(f).asInstanceOf[ComparablePredicate])))

  override def atoms: Seq[Predicate] = comparables.toSeq
}


class And(val a: Predicate, val b: Predicate) extends Predicate {
  def isMatch(m: ExecutionContext)(implicit state: QueryState): Option[Boolean] = Ands(List(a, b)).isMatch(m)

  override def atoms: Seq[Predicate] = a.atoms ++ b.atoms
  override def toString: String = "(" + a + " AND " + b + ")"
  def containsIsNull = a.containsIsNull||b.containsIsNull
  def rewrite(f: (Expression) => Expression) = f(And(a.rewriteAsPredicate(f), b.rewriteAsPredicate(f)))

  def arguments = Seq(a, b)

  override def hashCode() = a.hashCode + 37 * b.hashCode

  override def equals(p1: Any) = p1 match {
    case null       => false
    case other: And => a == other.a && b == other.b
    case _          => false
  }

  def symbolTableDependencies = a.symbolTableDependencies ++ b.symbolTableDependencies
}

case class Ors(predicates: List[Predicate]) extends CompositeBooleanPredicate {
  def shouldExitWhen = true
  def rewrite(f: (Expression) => Expression): Expression = f(Ors(predicates.map(_.rewriteAsPredicate(f))))
}

case class Or(a: Predicate, b: Predicate) extends Predicate {
  def isMatch(m: ExecutionContext)(implicit state: QueryState): Option[Boolean] = Ors(List(a, b)).isMatch(m)

  override def toString: String = "(" + a + " OR " + b + ")"
  def containsIsNull = a.containsIsNull||b.containsIsNull
  def rewrite(f: (Expression) => Expression) = f(Or(a.rewriteAsPredicate(f), b.rewriteAsPredicate(f)))

  def arguments = Seq(a, b)

  def symbolTableDependencies = a.symbolTableDependencies ++ b.symbolTableDependencies
}

abstract class CompositeBooleanPredicate extends Predicate {

  def predicates: List[Predicate]

  assert(predicates.nonEmpty, "Expected predicates to never be empty")

  def shouldExitWhen: Boolean

  def symbolTableDependencies: Set[String] = predicates.flatMap(_.symbolTableDependencies).toSet

  def containsIsNull: Boolean = predicates.exists(_.containsIsNull)

  /**
   * This algorithm handles the case where we combine multiple AND or multiple OR groups (CNF or DNF).
   * As well as performing shortcut evaluation so that a false (for AND) or true (for OR) will exit the
   * evaluation without performing any further predicate evaluations (including those that could throw
   * exceptions). Any exception thrown is held until the end (or until the exit state) so that it is
   * superceded by exit predicates (false for AND and true for OR).
   */
  def isMatch(m: ExecutionContext)(implicit state: QueryState): Option[Boolean] = {
    predicates.foldLeft[Try[Option[Boolean]]](Success(Some(!shouldExitWhen))) { (previousValue, predicate) =>
      previousValue match {
        // if a previous evaluation was true (false) the OR (AND) result is determined
        case Success(Some(result)) if result == shouldExitWhen => previousValue
        case _ =>
          Try(predicate.isMatch(m)) match {
            // If we get the exit case (false for AND and true for OR) ignore any error cases
            case Success(Some(result)) if result == shouldExitWhen => Success(Some(shouldExitWhen))
            // Handle null only for non error cases
            case Success(None) if previousValue.isSuccess => Success(None)
            // errors or non-exit cases propagate as normal
            case Failure(e) if previousValue.isSuccess => Failure(e)
            case _ => previousValue
          }
      }
    } match {
      case Failure(e) => throw e
      case Success(option) => option
    }
  }

  def arguments: Seq[Expression] = predicates

  override def atoms: Seq[Predicate] = predicates
}

case class Not(a: Predicate) extends Predicate {
  def isMatch(m: ExecutionContext)(implicit state: QueryState): Option[Boolean] = a.isMatch(m) match {
    case Some(x) => Some(!x)
    case None    => None
  }
  override def toString: String = "NOT(" + a + ")"
  def containsIsNull = a.containsIsNull
  def rewrite(f: (Expression) => Expression) = f(Not(a.rewriteAsPredicate(f)))
  def arguments = Seq(a)
  def symbolTableDependencies = a.symbolTableDependencies
}

case class Xor(a: Predicate, b: Predicate) extends Predicate {
  def isMatch(m: ExecutionContext)(implicit state: QueryState): Option[Boolean] = (a.isMatch(m), b.isMatch(m)) match {
    case (None, _)          => None
    case (_, None)          => None
    case (Some(l), Some(r)) => Some(l ^ r)
  }

  override def toString: String = "(" + a + " XOR " + b + ")"
  def containsIsNull = a.containsIsNull||b.containsIsNull
  def rewrite(f: (Expression) => Expression) = f(Xor(a.rewriteAsPredicate(f), b.rewriteAsPredicate(f)))

  def arguments = Seq(a, b)

  def symbolTableDependencies = a.symbolTableDependencies ++ b.symbolTableDependencies
}

case class IsNull(expression: Expression) extends Predicate {
  def isMatch(m: ExecutionContext)(implicit state: QueryState): Option[Boolean] = expression(m) match {
    case null => Some(true)
    case _    => Some(false)
  }

  override def toString: String = expression + " IS NULL"
  def containsIsNull = true
  def rewrite(f: (Expression) => Expression) = f(IsNull(expression.rewrite(f)))
  def arguments = Seq(expression)
  def symbolTableDependencies = expression.symbolTableDependencies
}

case class True() extends Predicate {
  def isMatch(m: ExecutionContext)(implicit state: QueryState): Option[Boolean] = Some(true)
  override def toString: String = "true"
  def containsIsNull = false
  def rewrite(f: (Expression) => Expression) = f(this)
  def arguments = Nil
  def symbolTableDependencies = Set()
}

case class PropertyExists(identifier: Expression, propertyKey: KeyToken) extends Predicate {
  def isMatch(m: ExecutionContext)(implicit state: QueryState): Option[Boolean] = identifier(m) match {
    case pc: Node         => Some(propertyKey.getOptId(state.query).exists(state.query.nodeOps.hasProperty(pc.getId, _)))
    case pc: Relationship => Some(propertyKey.getOptId(state.query).exists(state.query.relationshipOps.hasProperty(pc.getId, _)))
    case null             => None
    case _                => throw new CypherTypeException("Expected " + identifier + " to be a property container.")
  }

  override def toString: String = s"hasProp($identifier.${propertyKey.name})"

  def containsIsNull = false

  def rewrite(f: (Expression) => Expression) = f(PropertyExists(identifier.rewrite(f), propertyKey.rewrite(f)))

  def arguments = Seq(identifier)

  def symbolTableDependencies = identifier.symbolTableDependencies

  override def localEffects(symbols: SymbolTable) = Effects.propertyRead(identifier, symbols)(propertyKey.name)
}

case class LiteralLikePattern(predicate: Predicate, pattern: ParsedLikePattern, caseInsensitive: Boolean = false) extends Predicate {
  def isMatch(m: ExecutionContext)(implicit state: QueryState) = predicate.isMatch(m)
  def containsIsNull = predicate.containsIsNull

  def rewrite(f: (Expression) => Expression) = f(copy(predicate = f(predicate).asInstanceOf[Predicate]))

  def arguments = predicate.arguments

  def symbolTableDependencies = predicate.symbolTableDependencies
}

case class LiteralRegularExpression(lhsExpr: Expression, regexExpr: Literal)(implicit converter: String => String = identity) extends Predicate {
  lazy val pattern = converter(regexExpr.v.asInstanceOf[String]).r.pattern

  def isMatch(m: ExecutionContext)(implicit state: QueryState) =
    Option(lhsExpr(m)).map { lhsValue =>
      val v = CastSupport.castOrFail[String](lhsValue)
      pattern.matcher(v).matches()
    }

  def containsIsNull = false

  def rewrite(f: (Expression) => Expression) = f(regexExpr.rewrite(f) match {
    case lit: Literal => LiteralRegularExpression(lhsExpr.rewrite(f), lit)(converter)
    case other        => RegularExpression(lhsExpr.rewrite(f), other)(converter)
  })

  def arguments = Seq(lhsExpr, regexExpr)

  def symbolTableDependencies = lhsExpr.symbolTableDependencies ++ regexExpr.symbolTableDependencies
}

case class RegularExpression(lhsExpr: Expression, regexExpr: Expression)(implicit converter: String => String = identity) extends Predicate {
  def isMatch(m: ExecutionContext)(implicit state: QueryState): Option[Boolean] = (lhsExpr(m), regexExpr(m)) match {
    case (null, _) =>
      None
    case (_, null) =>
      None
    case (lhs, rhs)  =>
      val lhsAsString = CastSupport.castOrFail[String](lhs)
      val rhsAsRegexString = converter(CastSupport.castOrFail[String](rhs))
      Some(rhsAsRegexString.r.pattern.matcher(lhsAsString).matches())
  }

  override def toString: String = lhsExpr.toString() + " ~= /" + regexExpr.toString() + "/"

  def containsIsNull = false

  def rewrite(f: (Expression) => Expression) = f(regexExpr.rewrite(f) match {
    case lit:Literal => LiteralRegularExpression(lhsExpr.rewrite(f), lit)(converter)
    case other => RegularExpression(lhsExpr.rewrite(f), other)(converter)
  })

  def arguments = Seq(lhsExpr, regexExpr)

  def symbolTableDependencies = lhsExpr.symbolTableDependencies ++ regexExpr.symbolTableDependencies
}

case class NonEmpty(collection: Expression) extends Predicate with CollectionSupport {
  def isMatch(m: ExecutionContext)(implicit state: QueryState): Option[Boolean] = {
    collection(m) match {
      case IsCollection(x) => Some(x.nonEmpty)
      case null            => None
      case x               => throw new CypherTypeException("Expected a collection, got `%s`".format(x))
    }
  }

  override def toString: String = "nonEmpty(" + collection.toString() + ")"

  def containsIsNull = false

  def rewrite(f: (Expression) => Expression) = f(NonEmpty(collection.rewrite(f)))

  def arguments = Seq(collection)

  def symbolTableDependencies = collection.symbolTableDependencies
}

case class HasLabel(entity: Expression, label: KeyToken) extends Predicate {

  def isMatch(m: ExecutionContext)(implicit state: QueryState): Option[Boolean] = entity(m) match {

    case null =>
      None

    case value =>
      val node           = CastSupport.castOrFail[Node](value)
      val nodeId         = node.getId
      val queryCtx       = state.query

      label.getOptId(state.query) match {
        case None =>
          Some(false)
        case Some(labelId) =>
          Some(queryCtx.isLabelSetOnNode(labelId, nodeId))
      }
  }

  override def toString = s"$entity:${label.name}"

  def rewrite(f: (Expression) => Expression) = f(HasLabel(entity.rewrite(f), label.typedRewrite[KeyToken](f)))

  override def children = Seq(label, entity)

  def arguments: Seq[Expression] = Seq(entity)

  def symbolTableDependencies = entity.symbolTableDependencies ++ label.symbolTableDependencies

  def containsIsNull = false

  override def localEffects(symbols: SymbolTable) = Effects(ReadsLabel(label.name))
}

case class CoercedPredicate(inner:Expression) extends Predicate with CollectionSupport {
  def arguments = Seq(inner)

  def isMatch(m: ExecutionContext)(implicit state: QueryState) = inner(m) match {
    case x: Boolean         => Some(x)
    case null               => None
    case IsCollection(coll) => Some(coll.nonEmpty)
    case x                  => throw new CypherTypeException(s"Don't know how to treat that as a predicate: $x")
  }

  def rewrite(f: (Expression) => Expression) = f(CoercedPredicate(inner.rewrite(f)))

  def containsIsNull = false

  def symbolTableDependencies = inner.symbolTableDependencies

  override def toString = inner.toString
}
