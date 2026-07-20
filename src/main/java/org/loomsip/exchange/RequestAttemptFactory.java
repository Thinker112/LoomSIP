package org.loomsip.exchange;

/**
 * Starts one concrete transaction for an immutable request attempt.
 *
 * @param <T> transaction handle type
 */
@FunctionalInterface
public interface RequestAttemptFactory<T> {

    /**
     * Starts one transaction attempt.
     *
     * <p>The factory runs on the exchange executor. It should register and
     * start the transaction without waiting for a SIP response.</p>
     *
     * @param context immutable attempt context
     * @return non-null concrete transaction handle
     * @throws Exception if request validation or transaction creation fails
     */
    T start(RequestAttemptContext context) throws Exception;
}
