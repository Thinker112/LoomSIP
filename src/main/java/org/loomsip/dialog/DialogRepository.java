package org.loomsip.dialog;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Concurrent identity and lifecycle index for active Dialogs. */
public interface DialogRepository {

    /**
     * Finds an active Dialog by its full identity.
     *
     * @param id Dialog identity
     * @return Dialog matching {@code id}, when active
     */
    Optional<SipDialog> find(DialogId id);

    /**
     * Returns the existing Dialog or atomically creates one.
     *
     * @param id requested identity
     * @param factory candidate factory
     * @return existing or newly registered Dialog
     */
    SipDialog getOrCreate(DialogId id, Supplier<? extends SipDialog> factory);

    /**
     * Finds active Dialogs created from one fork-related context.
     *
     * @param setId Dialog Set identity
     * @return immutable active Dialogs belonging to one fork set
     */
    List<SipDialog> findBySet(DialogSetId setId);

    /**
     * Removes only the expected Dialog instance.
     *
     * @param id Dialog identity
     * @param expected instance that must currently be registered
     * @return whether removal occurred
     */
    boolean remove(DialogId id, SipDialog expected);

    /**
     * Returns a stable view of the current repository contents.
     *
     * @return immutable snapshot of all active Dialogs
     */
    List<SipDialog> dialogs();

    /**
     * Returns the current repository size.
     *
     * @return active Dialog count
     */
    int size();

    /**
     * Returns the configured repository limit.
     *
     * @return maximum active Dialog count
     */
    int capacity();
}
