package org.loomsip.dialog;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipUri;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryDialogRepositoryTest {

    @Test
    void getOrCreateRegistersOnlyOneInstance() {
        InMemoryDialogRepository repository = new InMemoryDialogRepository(2);
        DialogId id = id("remote-a");
        AtomicInteger factoryCalls = new AtomicInteger();

        SipDialog first = repository.getOrCreate(id, () -> {
            factoryCalls.incrementAndGet();
            return dialog(id);
        });
        SipDialog second = repository.getOrCreate(id, () -> {
            factoryCalls.incrementAndGet();
            return dialog(id);
        });

        assertSame(first, second);
        assertEquals(1, factoryCalls.get());
        assertEquals(1, repository.size());
    }

    @Test
    void enforcesCapacityBeforeInvokingFactory() {
        InMemoryDialogRepository repository = new InMemoryDialogRepository(1);
        repository.getOrCreate(id("remote-a"), () -> dialog(id("remote-a")));
        AtomicInteger factoryCalls = new AtomicInteger();

        assertThrows(DialogRepositoryException.class, () -> repository.getOrCreate(
                id("remote-b"),
                () -> {
                    factoryCalls.incrementAndGet();
                    return dialog(id("remote-b"));
                }
        ));
        assertEquals(0, factoryCalls.get());
    }

    @Test
    void maintainsForkIndexAndUsesExpectedInstanceRemoval() {
        InMemoryDialogRepository repository = new InMemoryDialogRepository(3);
        DialogId firstId = id("remote-a");
        DialogId secondId = id("remote-b");
        SipDialog first = repository.getOrCreate(firstId, () -> dialog(firstId));
        SipDialog second = repository.getOrCreate(secondId, () -> dialog(secondId));
        SipDialog impostor = dialog(firstId);

        assertEquals(List.of(firstId, secondId), repository.findBySet(firstId.setId())
                .stream().map(SipDialog::id).toList());
        assertFalse(repository.remove(firstId, impostor));
        assertSame(first, repository.find(firstId).orElseThrow());

        assertTrue(repository.remove(firstId, first));
        assertEquals(List.of(secondId), repository.findBySet(firstId.setId())
                .stream().map(SipDialog::id).toList());
        assertTrue(repository.remove(secondId, second));
        assertEquals(List.of(), repository.findBySet(firstId.setId()));
        assertEquals(0, repository.size());
    }

    private static SipDialog dialog(DialogId id) {
        return new SipDialog(
                new DialogSnapshot(
                        id,
                        DialogRole.UAC,
                        DialogState.EARLY,
                        SipUri.parse("sip:alice@example.com"),
                        SipUri.parse("sip:bob@example.com"),
                        1,
                        1,
                        List.of(),
                        Optional.empty(),
                        false
                ),
                Runnable::run,
                Runnable::run,
                new DialogConfig(3, 8, 8),
                new DialogLifecycleListener() {
                },
                ignored -> {
                }
        );
    }

    private static DialogId id(String remoteTag) {
        return new DialogId("call-1@example.com", "local", remoteTag);
    }
}
