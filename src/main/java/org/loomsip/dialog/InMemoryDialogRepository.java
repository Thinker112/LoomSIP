package org.loomsip.dialog;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Capacity-limited in-memory Dialog repository with consistent fork-set indexing.
 *
 * <pre>{@code
 * DialogId ---------> primary map -------> SipDialog
 *    |
 *    v
 * DialogSetId ------> secondary set -----> DialogId entries
 * }</pre>
 */
public final class InMemoryDialogRepository implements DialogRepository {

    private final Object monitor = new Object();
    private final Map<DialogId, SipDialog> dialogs = new HashMap<>();
    private final Map<DialogSetId, Set<DialogId>> dialogSets = new HashMap<>();
    private final int capacity;

    /**
     * Creates an empty repository.
     *
     * @param capacity maximum active Dialog count
     */
    public InMemoryDialogRepository(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Dialog repository capacity must be positive");
        }
        this.capacity = capacity;
    }

    @Override
    public Optional<SipDialog> find(DialogId id) {
        synchronized (monitor) {
            return Optional.ofNullable(dialogs.get(Objects.requireNonNull(id, "id")));
        }
    }

    @Override
    public SipDialog getOrCreate(DialogId id, Supplier<? extends SipDialog> factory) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(factory, "factory");
        synchronized (monitor) {
            SipDialog existing = dialogs.get(id);
            if (existing != null) {
                return existing;
            }
            if (dialogs.size() >= capacity) {
                throw new DialogRepositoryException("Dialog repository capacity reached: " + capacity);
            }
            final SipDialog candidate;
            try {
                candidate = Objects.requireNonNull(factory.get(), "factory result");
            } catch (DialogRepositoryException exception) {
                throw exception;
            } catch (Throwable cause) {
                throw new DialogRepositoryException("Dialog factory failed", cause);
            }
            if (!id.equals(candidate.id())) {
                throw new DialogRepositoryException("candidate Dialog ID does not match requested ID");
            }
            dialogs.put(id, candidate);
            dialogSets.computeIfAbsent(id.setId(), ignored -> new LinkedHashSet<>()).add(id);
            return candidate;
        }
    }

    @Override
    public List<SipDialog> findBySet(DialogSetId setId) {
        synchronized (monitor) {
            Set<DialogId> ids = dialogSets.get(Objects.requireNonNull(setId, "setId"));
            if (ids == null) {
                return List.of();
            }
            return ids.stream().map(dialogs::get).filter(Objects::nonNull).toList();
        }
    }

    @Override
    public boolean remove(DialogId id, SipDialog expected) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(expected, "expected");
        synchronized (monitor) {
            if (dialogs.get(id) != expected) {
                return false;
            }
            dialogs.remove(id);
            Set<DialogId> ids = dialogSets.get(id.setId());
            if (ids != null) {
                ids.remove(id);
                if (ids.isEmpty()) {
                    dialogSets.remove(id.setId());
                }
            }
            return true;
        }
    }

    @Override
    public List<SipDialog> dialogs() {
        synchronized (monitor) {
            return List.copyOf(dialogs.values());
        }
    }

    @Override
    public int size() {
        synchronized (monitor) {
            return dialogs.size();
        }
    }

    @Override
    public int capacity() {
        return capacity;
    }
}
