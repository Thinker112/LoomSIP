package org.loomsip.transaction.invite;

import org.loomsip.transaction.SipTransaction;
import org.loomsip.transaction.TransactionKey;
import org.loomsip.transaction.TransactionMessageSender;
import org.loomsip.transaction.internal.AbstractTransaction;
import org.loomsip.transaction.timer.SipScheduler;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Adapts the shared transaction execution boundary to INVITE configuration.
 *
 * <pre>{@code
 * ICT / IST
 *    |
 *    v
 * AbstractInviteTransaction
 *    |
 *    v
 * AbstractTransaction
 *    |
 *    +--> Mailbox / Timer / Sender / TU callbacks
 * }</pre>
 */
abstract class AbstractInviteTransaction extends AbstractTransaction {

    AbstractInviteTransaction(
            TransactionKey key,
            TransactionMessageSender sender,
            SipScheduler scheduler,
            Executor transactionExecutor,
            Executor callbackExecutor,
            InviteTransactionConfig config,
            Consumer<? super SipTransaction> terminationCallback,
            Consumer<? super Throwable> infrastructureErrorHandler
    ) {
        super(
                key,
                sender,
                scheduler,
                transactionExecutor,
                callbackExecutor,
                config.mailboxCapacity(),
                config.callbackCapacity(),
                terminationCallback,
                infrastructureErrorHandler
        );
    }
}
