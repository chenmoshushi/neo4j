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
package org.neo4j.impl.kernel.api;

/**
 * Token creation and lookup.
 */
public interface Token
{
    class SomeException extends Exception
    {
    }

    /**
     * Returns a label id for a label name. If the label doesn't exist prior to
     * this call it gets created.
     */
    int labelGetOrCreateForName( String labelName ) throws SomeException;

    /**
     * Returns a property key id for a property key. If the key doesn't exist prior to
     * this call it gets created.
     */
    int propertyKeyGetOrCreateForName( String propertyKeyName ) throws SomeException;

    int relationshipTypeGetOrCreateForName( String relationshipTypeName ) throws SomeException;

    void labelCreateForName( String labelName, int id ) throws SomeException;

    void propertyKeyCreateForName( String propertyKeyName, int id ) throws SomeException;

    void relationshipTypeCreateForName( String relationshipTypeName, int id ) throws SomeException;
}
