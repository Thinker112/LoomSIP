package org.loomsip.subscription;

import org.junit.jupiter.api.Test;
import org.loomsip.message.header.EventHeaderValue;
import org.loomsip.message.header.SubscriptionState;
import org.loomsip.message.header.SubscriptionStateHeaderValue;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubscriptionManagerTest {

    @Test
    void serializesActivationAndTerminalCleanup() {
        SubscriptionManager manager = new SubscriptionManager(SubscriptionConfig.DEFAULT, Runnable::run, failure -> {
            throw new AssertionError(failure);
        });
        SubscriptionId id = id("presence", Optional.of("watcher-1"));

        SubscriptionHandle first = manager.create(id);
        assertSame(first, manager.create(id));
        assertEquals(SubscriptionLifecycleState.PENDING, first.snapshot().state());
        assertEquals(SubscriptionLifecycleState.ACTIVE, manager.activate(id).toCompletableFuture().join().state());

        manager.terminate(id, SubscriptionTerminationReason.REMOTE_TERMINATED).toCompletableFuture().join();

        assertEquals(SubscriptionLifecycleState.TERMINATED, first.snapshot().state());
        assertEquals(Optional.of(SubscriptionTerminationReason.REMOTE_TERMINATED), first.snapshot().terminationReason());
        first.terminated().toCompletableFuture().join();
        assertEquals(0, manager.size());
    }

    @Test
    void closeTerminatesPendingAndRejectsNewSubscriptions() {
        SubscriptionManager manager = new SubscriptionManager(SubscriptionConfig.DEFAULT, Runnable::run, failure -> {
            throw new AssertionError(failure);
        });
        SubscriptionId id = id("refer", Optional.empty());
        SubscriptionHandle handle = manager.create(id);

        manager.close();

        handle.terminated().toCompletableFuture().join();
        assertEquals(SubscriptionLifecycleState.TERMINATED, handle.snapshot().state());
        assertEquals(Optional.of(SubscriptionTerminationReason.MANAGER_CLOSED), handle.snapshot().terminationReason());
        assertThrows(IllegalStateException.class, () -> manager.create(id("presence", Optional.empty())));
    }

    @Test
    void notifyLifecycleUpdatesAndTerminatedNotifyRemovesSubscription() {
        SubscriptionManager manager = new SubscriptionManager(SubscriptionConfig.DEFAULT, Runnable::run, failure -> {
            throw new AssertionError(failure);
        });
        SubscriptionId id = id("presence", Optional.empty());
        SubscriptionHandle handle = manager.create(id);

        assertEquals(SubscriptionLifecycleState.ACTIVE, manager.onNotify(id, state(SubscriptionState.ACTIVE))
                .toCompletableFuture().join().state());
        assertEquals(SubscriptionLifecycleState.TERMINATED, manager.onNotify(id, state(SubscriptionState.TERMINATED))
                .toCompletableFuture().join().state());
        assertEquals(Optional.of(SubscriptionTerminationReason.REMOTE_TERMINATED), handle.snapshot().terminationReason());
        assertEquals(0, manager.size());
        assertThrows(IllegalArgumentException.class, () -> manager.onNotify(id, state(SubscriptionState.ACTIVE)));
    }

    private static SubscriptionId id(String event, Optional<String> eventId) {
        return new SubscriptionId("subscription@example.com", "local-tag", "remote-tag", new EventHeaderValue(event, eventId));
    }

    private static SubscriptionStateHeaderValue state(SubscriptionState state) {
        return new SubscriptionStateHeaderValue(state, Optional.empty(), Optional.empty(), Optional.empty());
    }
}
