package com.aicodeassistant.security;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 同一规范工作区路径的进程内串行化权威，用于协调文件修改与完整性观察。
 */
@Component
public class ManagedPathLockManager {
    private final ConcurrentHashMap<String, LockEntry> locks = new ConcurrentHashMap<>();

    public <T, E extends Exception> T withLock(Path canonicalPath, CheckedSupplier<T, E> operation) throws E {
        String key = canonicalPath.toAbsolutePath().normalize().toString();
        LockEntry entry = locks.compute(key, (ignored, current) -> {
            LockEntry selected = current == null ? new LockEntry() : current;
            selected.references.incrementAndGet();
            return selected;
        });
        entry.lock.lock();
        try {
            return operation.get();
        } finally {
            entry.lock.unlock();
            locks.computeIfPresent(key, (ignored, current) ->
                    current == entry && current.references.decrementAndGet() == 0 ? null : current);
        }
    }

    @FunctionalInterface
    public interface CheckedSupplier<T, E extends Exception> { T get() throws E; }

    private static final class LockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger references = new AtomicInteger();
    }
}
