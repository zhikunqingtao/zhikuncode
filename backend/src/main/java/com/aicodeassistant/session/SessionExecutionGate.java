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

    public Token tryAcquire(DataSource dataSource, String sessionId) {
        Key key = new Key(dataSource, sessionId);
        Owner owner = new Owner();
        if (owners.putIfAbsent(key, owner) != null) return null;
        return new Token(this, key, owner);
    }

    public boolean isBusy(DataSource dataSource, String sessionId) {
        return owners.containsKey(new Key(dataSource, sessionId));
    }

    private void release(Key key, Owner owner) { owners.remove(key, owner); }
    private static final class Owner { }

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
