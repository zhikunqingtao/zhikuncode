package com.aicodeassistant.session;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Non-blocking, cross-thread session execution ownership. */
@Component
public final class SessionExecutionGate {
    private final ConcurrentHashMap<Key, Owner> owners = new ConcurrentHashMap<>();
    private final java.util.Map<Key, Integer> background = new java.util.HashMap<>();

    public synchronized Token tryAcquire(DataSource dataSource, String sessionId) {
        Key key = new Key(dataSource, sessionId);
        Owner owner = new Owner(null);
        if (owners.putIfAbsent(key, owner) != null) return null;
        return new Token(this, key, owner);
    }

    /** Merge excludes both foreground owners and actual (not merely timed-out) workers. */
    public synchronized Token tryAcquireMerge(DataSource ds, String sessionId, String operationId) {
        Key key = new Key(ds, sessionId);
        if (owners.containsKey(key) || background.getOrDefault(key, 0) > 0) return null;
        Owner owner = new Owner(Objects.requireNonNull(operationId));
        owners.put(key, owner);
        return new Token(this, key, owner);
    }

    /** Normal queries can start their own workers. Only a merge excludes admission. */
    public synchronized BackgroundLease acquireBackground(DataSource ds, String sessionId) {
        Key key = new Key(ds, sessionId);
        assertNotMerging(ds, sessionId);
        background.merge(key, 1, Integer::sum);
        return new BackgroundLease(this, key);
    }

    public String mergeOperationId(DataSource ds, String sessionId) {
        Owner owner = owners.get(new Key(ds, sessionId));
        return owner == null ? null : owner.operationId;
    }

    public void assertNotMerging(DataSource ds, String sessionId) {
        if (mergeOperationId(ds, sessionId) != null) throw new SessionExecutionBusyException(sessionId);
    }

    private synchronized void releaseBackground(Key key) {
        background.computeIfPresent(key, (ignored, count) -> count <= 1 ? null : count - 1);
    }

    public boolean isBusy(DataSource dataSource, String sessionId) {
        return owners.containsKey(new Key(dataSource, sessionId));
    }

    private synchronized void release(Key key, Owner owner) { owners.remove(key, owner); }
    private static final class Owner {
        final String operationId;
        Owner(String operationId) { this.operationId = operationId; }
    }

    public static final class BackgroundLease implements AutoCloseable {
        private final SessionExecutionGate gate;
        private final Key key;
        private final AtomicBoolean closed = new AtomicBoolean();
        private BackgroundLease(SessionExecutionGate gate, Key key) { this.gate = gate; this.key = key; }
        @Override public void close() { if (closed.compareAndSet(false, true)) gate.releaseBackground(key); }
    }

    private static final class Key {
        private final DataSource dataSource;
        private final String sessionId;
        private Key(DataSource dataSource, String sessionId) {
            this.dataSource = Objects.requireNonNull(dataSource);
            this.sessionId = Objects.requireNonNull(sessionId);
        }
        @Override public boolean equals(Object other) {
            return other instanceof Key key
                    && dataSource == key.dataSource && sessionId.equals(key.sessionId);
        }
        @Override public int hashCode() {
            return 31 * System.identityHashCode(dataSource) + sessionId.hashCode();
        }
    }

    public static final class Token implements AutoCloseable {
        private final SessionExecutionGate gate;
        private final Key key;
        private final Owner owner;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Token(SessionExecutionGate gate, Key key, Owner owner) {
            this.gate = gate; this.key = key; this.owner = owner;
        }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) gate.release(key, owner);
        }
    }
}
