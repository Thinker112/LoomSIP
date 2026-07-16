package org.loomsip.transaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.loomsip.message.SipMethod;
import org.loomsip.message.header.SentBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class InMemoryTransactionRepositoryTest {

    @Test
    void registersFindsAndRemovesOnlyTheExpectedIdentity() {
        InMemoryTransactionRepository repository = new InMemoryTransactionRepository(2);
        TestTransaction transaction = new TestTransaction(key("one"));
        TestTransaction equalKeyButDifferentObject = new TestTransaction(key("one"));

        repository.register(transaction);

        assertSame(transaction, repository.find(transaction.key()).orElseThrow());
        assertFalse(repository.remove(transaction.key(), equalKeyButDifferentObject));
        assertEquals(1, repository.size());
        assertTrue(repository.remove(transaction.key(), transaction));
        assertEquals(0, repository.size());
    }

    @Test
    void enforcesCapacityAndDuplicateKeys() {
        InMemoryTransactionRepository repository = new InMemoryTransactionRepository(1);
        repository.register(new TestTransaction(key("one")));

        assertThrows(TransactionRepositoryException.class,
                () -> repository.register(new TestTransaction(key("two"))));
        assertThrows(TransactionRepositoryException.class,
                () -> repository.register(new TestTransaction(key("one"))));
        assertEquals(1, repository.size());
    }

    @Test
    void concurrentlyCreatesOneInstanceForTheSameKey() throws Exception {
        InMemoryTransactionRepository repository = new InMemoryTransactionRepository(1);
        TransactionKey key = key("shared");
        AtomicInteger creations = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<SipTransaction>> results = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return repository.getOrCreate(key, () -> {
                        creations.incrementAndGet();
                        return new TestTransaction(key);
                    });
                }));
            }
            start.countDown();

            SipTransaction winner = results.getFirst().get();
            for (Future<SipTransaction> result : results) {
                assertSame(winner, result.get());
            }
        }

        assertEquals(1, creations.get());
        assertEquals(1, repository.size());
    }

    private static Rfc3261TransactionKey key(String suffix) {
        return new Rfc3261TransactionKey(
                "z9hG4bK-" + suffix,
                new SentBy("example.com", 5060),
                SipMethod.OPTIONS
        );
    }

    private record TestTransaction(TransactionKey key) implements SipTransaction {
    }
}
