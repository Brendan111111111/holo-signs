package com.brendan.holograms;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pending display updates for projectors. A projector has no custom equality, so each block entity
 * appears at most once per snapshot.
 * <p>
 * Terminal writes arrive from OpenComputers' computer threads as well as from the server thread,
 * so {@link #drain()} must tolerate values being added while it takes its snapshot. The queue is
 * intentionally tiny; it exists to keep that concurrency contract in one place and testable.
 */
final class ProjectorUpdateQueue<T> {
    private final Set<T> pending = ConcurrentHashMap.newKeySet();

    void add(T value) {
        if (value != null) pending.add(value);
    }

    void remove(T value) {
        pending.remove(value);
    }

    boolean contains(T value) {
        return pending.contains(value);
    }

    /**
     * Removes and returns the currently pending values. Values added concurrently may land in this
     * snapshot or the next one, but are never lost: clearing the set separately could remove an
     * update that arrived between the snapshot and the clear. Each value is removed atomically, so
     * it is only reported once.
     */
    List<T> drain() {
        if (pending.isEmpty()) return List.of();
        List<T> snapshot = new ArrayList<>();
        for (T value : pending) {
            if (pending.remove(value)) snapshot.add(value);
        }
        return snapshot;
    }

    int size() {
        return pending.size();
    }
}
