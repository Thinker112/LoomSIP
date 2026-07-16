package org.loomsip.transaction;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Capacity-limited concurrent in-memory transaction repository.
 */
public final class InMemoryTransactionRepository implements TransactionRepository {

    private final ConcurrentHashMap<TransactionKey, SipTransaction> transactions = new ConcurrentHashMap<>();
    private final AtomicInteger size = new AtomicInteger();
    private final int capacity;

    /**
     * Creates an empty repository.
     *
     * @param capacity maximum number of active transactions
     * @throws IllegalArgumentException if capacity is not positive
     */
    public InMemoryTransactionRepository(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("transaction repository capacity must be positive");
        }
        this.capacity = capacity;
    }

    @Override
    public Optional<SipTransaction> find(TransactionKey key) {
        return Optional.ofNullable(transactions.get(Objects.requireNonNull(key, "key")));
    }

    @Override
    public void register(SipTransaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        reserveSlot();
        boolean registered = false;
        try {
            SipTransaction existing = transactions.putIfAbsent(transaction.key(), transaction);
            if (existing != null) {
                throw new TransactionRepositoryException("transaction key is already registered: " + transaction.key());
            }
            registered = true;
        } finally {
            if (!registered) {
                releaseSlot();
            }
        }
    }

    @Override
    public SipTransaction getOrCreate(
            TransactionKey key,
            Supplier<? extends SipTransaction> factory
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(factory, "factory");
        try {
            return transactions.computeIfAbsent(key, ignored -> createReserved(key, factory));
        } catch (TransactionRepositoryException exception) {
            throw exception;
        } catch (Throwable cause) {
            throw new TransactionRepositoryException("transaction factory failed", cause);
        }
    }

    @Override
    public boolean remove(TransactionKey key, SipTransaction expected) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(expected, "expected");
        AtomicBoolean removed = new AtomicBoolean();
        transactions.computeIfPresent(key, (ignored, current) -> {
            if (current == expected) {
                removed.set(true);
                return null;
            }
            return current;
        });
        if (removed.get()) {
            releaseSlot();
            return true;
        }
        return false;
    }

    @Override
    public int size() {
        return size.get();
    }

    @Override
    public int capacity() {
        return capacity;
    }

    private void reserveSlot() {
        while (true) {
            int current = size.get();
            if (current >= capacity) {
                throw new TransactionRepositoryException(
                        "transaction repository capacity reached: " + capacity
                );
            }
            if (size.compareAndSet(current, current + 1)) {
                return;
            }
        }
    }

    private SipTransaction createReserved(
            TransactionKey key,
            Supplier<? extends SipTransaction> factory
    ) {
        reserveSlot();
        boolean registered = false;
        try {
            SipTransaction candidate = Objects.requireNonNull(factory.get(), "factory result");
            if (!key.equals(candidate.key())) {
                throw new TransactionRepositoryException("candidate transaction key does not match requested key");
            }
            registered = true;
            return candidate;
        } finally {
            if (!registered) {
                releaseSlot();
            }
        }
    }

    private void releaseSlot() {
        int remaining = size.decrementAndGet();
        if (remaining < 0) {
            throw new IllegalStateException("transaction repository size became negative");
        }
    }
}
