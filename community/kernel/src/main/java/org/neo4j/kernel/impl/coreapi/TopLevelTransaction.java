/*
 * Copyright (c) 2002-2016 "Neo Technology,"
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
package org.neo4j.kernel.impl.coreapi;

import java.util.function.Supplier;

import org.neo4j.graphdb.ConstraintViolationException;
import org.neo4j.graphdb.Lock;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.TransactionFailureException;
import org.neo4j.graphdb.TransientFailureException;
import org.neo4j.graphdb.TransientTransactionFailureException;
import org.neo4j.kernel.api.security.AccessMode;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.Statement;
import org.neo4j.kernel.api.exceptions.ConstraintViolationTransactionFailureException;
import org.neo4j.kernel.api.exceptions.KernelException;
import org.neo4j.kernel.api.exceptions.Status.Classification;

public class TopLevelTransaction implements InternalTransaction
{
    private final static PropertyContainerLocker locker = new PropertyContainerLocker();
    private boolean successCalled;
    private final Supplier<Statement> stmt;
    private final KernelTransaction transaction;

    public TopLevelTransaction( KernelTransaction transaction, Supplier<Statement> stmt )
    {
        this.stmt = stmt;
        this.transaction = transaction;
    }

    @Override
    public void failure()
    {
        transaction.failure();
    }

    @Override
    public void success()
    {
        successCalled = true;
        transaction.success();
    }

    @Override
    public final void terminate()
    {
        this.transaction.markForTermination();
    }

    @Override
    public void close()
    {
        try
        {
            if ( transaction.isOpen() )
            {
                transaction.close();
            }
        }
        catch ( TransientFailureException e )
        {
            // We let transient exceptions pass through unchanged since they aren't really transaction failures
            // in the same sense as unexpected failures are. Such exception signals that the transaction
            // can be retried and might be successful the next time.
            throw e;
        }
        catch ( ConstraintViolationTransactionFailureException e)
        {
            throw new ConstraintViolationException( e.getMessage(), e );
        }
        catch ( Exception e )
        {
            String userMessage = successCalled
                    ? "Transaction was marked as successful, but unable to commit transaction so rolled back."
                    : "Unable to rollback transaction";
            if ( e instanceof KernelException &&
                    ((KernelException)e).status().code().classification() == Classification.TransientError )
            {
                throw new TransientTransactionFailureException( userMessage, e );
            }
            throw new TransactionFailureException( userMessage, e );
        }
    }

    @Override
    public Lock acquireWriteLock( PropertyContainer entity )
    {
        return locker.exclusiveLock( stmt, entity );
    }

    @Override
    public Lock acquireReadLock( PropertyContainer entity )
    {
        return locker.sharedLock(stmt, entity);
    }

    @Override
    public KernelTransaction.Type transactionType()
    {
        return transaction.transactionType();
    }

    @Override
    public AccessMode mode()
    {
        return transaction.mode();
    }

    @Override
    public KernelTransaction.Revertable restrict( AccessMode mode )
    {
        return transaction.restrict( mode );
    }
}
