/*
 * Copyright (c) 2002-2017 "Neo Technology,"
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
package org.neo4j.cypher.internal.compatibility.v3_3.runtime.pipes

import org.mockito.Matchers
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.neo4j.cypher.internal.compatibility.v3_3.runtime.ExecutionContext
import org.neo4j.cypher.internal.frontend.v3_3.SemanticDirection
import org.neo4j.cypher.internal.frontend.v3_3.test_helpers.CypherFunSuite
import org.neo4j.cypher.internal.spi.v3_3.QueryContext
import org.neo4j.graphdb.{Node, Relationship}
import org.neo4j.values.AnyValues
import org.neo4j.values.virtual.VirtualValues.{fromNodeProxy, fromRelationshipProxy}

class ExpandAllPipeTest extends CypherFunSuite {

  val startNode = newMockedNode(1)
  val endNode1 = newMockedNode(2)
  val endNode2 = newMockedNode(3)
  val relationship1 = newMockedRelationship(1, startNode, endNode1)
  val relationship2 = newMockedRelationship(2, startNode, endNode2)
  val selfRelationship = newMockedRelationship(3, startNode, startNode)
  val query = mock[QueryContext]
  val queryState = QueryStateHelper.emptyWith(query = query)

  test("should support expand between two nodes with a relationship") {
    // given
    mockRelationships(relationship1)
    val left = newMockedPipe("a",
      row("a" -> startNode))

    // when
    val result = ExpandAllPipe(left, "a", "r", "b", SemanticDirection.OUTGOING, LazyTypes.empty)().createResults(queryState).toList

    // then
    val (single :: Nil) = result
    single.toMap should equal(Map("a" -> fromNodeProxy(startNode), "r" -> fromRelationshipProxy(relationship1),
                              "b" -> fromNodeProxy(endNode1)))
  }

  test("should return no relationships for types that have not been defined yet") {
    // given
    when(query.getRelationshipsForIds(any(), any(), Matchers.eq(Some(Seq.empty)))).thenAnswer(new Answer[Iterator[Relationship]]{
      override def answer(invocationOnMock: InvocationOnMock): Iterator[Relationship] = Iterator.empty
    })
    when(query.getRelationshipsForIds(any(), any(), Matchers.eq(Some(Seq(1,2))))).thenAnswer(new Answer[Iterator[Relationship]]{
      override def answer(invocationOnMock: InvocationOnMock): Iterator[Relationship] = Iterator(relationship1, relationship2)
    })

    val pipe = ExpandAllPipe(newMockedPipe("a", row("a"-> startNode)), "a", "r", "b", SemanticDirection.OUTGOING, LazyTypes(Seq("FOO", "BAR")))()

    // when
    when(query.getOptRelTypeId("FOO")).thenReturn(None)
    when(query.getOptRelTypeId("BAR")).thenReturn(None)
    val result1 = pipe.createResults(queryState).toList

    // when
    when(query.getOptRelTypeId("FOO")).thenReturn(Some(1))
    when(query.getOptRelTypeId("BAR")).thenReturn(Some(2))
    val result2 = pipe.createResults(queryState).toList

    // then
    result1 should be(empty)
    result2 should not be empty
  }

  test("should support expand between two nodes with multiple relationships") {
    // given
    mockRelationships(relationship1, relationship2)
    val left = newMockedPipe("a",
      row("a" -> startNode))

    // when
    val result = ExpandAllPipe(left, "a", "r", "b", SemanticDirection.OUTGOING, LazyTypes.empty)().createResults(queryState).toList

    // then
    val (first :: second :: Nil) = result
    first.toMap should equal(Map("a" -> fromNodeProxy(startNode), "r" -> fromRelationshipProxy(relationship1), "b" -> fromNodeProxy(endNode1)))
    second.toMap should equal(Map("a" -> fromNodeProxy(startNode), "r" -> fromRelationshipProxy(relationship2), "b" -> fromNodeProxy(endNode2)))
  }

  test("should support expand between two nodes with multiple relationships and self loops") {
    // given
    mockRelationships(relationship1, selfRelationship)
    val left = newMockedPipe("a",
      row("a" -> startNode))

    // when
    val result = ExpandAllPipe(left, "a", "r", "b", SemanticDirection.OUTGOING, LazyTypes.empty)().createResults(queryState).toList

    // then
    val (first :: second :: Nil) = result
    first.toMap should equal(Map("a" -> fromNodeProxy(startNode), "r" -> fromRelationshipProxy(relationship1), "b" -> fromNodeProxy(endNode1)))
    second.toMap should equal(Map("a" -> fromNodeProxy(startNode), "r" -> fromRelationshipProxy(selfRelationship), "b" -> fromNodeProxy(startNode)))
  }

  test("given empty input, should return empty output") {
    // given
    mockRelationships()
    val left = newMockedPipe("a", row("a" -> null))

    // when
    val result = ExpandAllPipe(left, "a", "r", "b", SemanticDirection.OUTGOING, LazyTypes.empty)().createResults(queryState).toList

    // then
    result should be (empty)
  }

  test("given a null start point, returns an empty iterator") {
    // given
    mockRelationships(relationship1)
    val left = newMockedPipe("a",
      row("a" -> startNode))

    // when
    val result = ExpandAllPipe(left, "a", "r", "b", SemanticDirection.OUTGOING, LazyTypes.empty)().createResults(queryState).toList

    // then
    val (single :: Nil) = result
    single.toMap should equal(Map("a" -> fromNodeProxy(startNode), "r" -> fromRelationshipProxy(relationship1), "b" -> fromNodeProxy(endNode1)))
  }

  private def row(values: (String, Any)*) = ExecutionContext.from(values.map(v => (v._1, AnyValues.of(v._2))): _*)

  private def mockRelationships(rels: Relationship*) {
    when(query.getRelationshipsForIds(any(), any(), any())).thenAnswer(new Answer[Iterator[Relationship]] {
      def answer(invocation: InvocationOnMock): Iterator[Relationship] = rels.iterator
    })
  }

  private def newMockedNode(id: Int) = {
    val node = mock[Node]
    when(node.getId).thenReturn(id)
    node
  }

  private def newMockedRelationship(id: Int, startNode: Node, endNode: Node): Relationship = {
    val relationship = mock[Relationship]
    val startId = startNode.getId
    val endId = endNode.getId
    when(relationship.getId).thenReturn(id)
    when(relationship.getStartNode).thenReturn(startNode)
    when(relationship.getStartNodeId).thenReturn(startId)
    when(relationship.getEndNode).thenReturn(endNode)
    when(relationship.getEndNodeId).thenReturn(endId)
    when(relationship.getOtherNode(startNode)).thenReturn(endNode)
    when(relationship.getOtherNode(endNode)).thenReturn(startNode)
    relationship
  }

  private def newMockedPipe(node: String, rows: ExecutionContext*): Pipe = {
    val pipe = mock[Pipe]
    when(pipe.createResults(any())).thenAnswer(new Answer[Iterator[ExecutionContext]] {
      def answer(invocation: InvocationOnMock): Iterator[ExecutionContext] = rows.iterator
    })

    pipe
  }
}
