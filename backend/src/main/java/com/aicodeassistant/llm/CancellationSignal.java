package com.aicodeassistant.llm;

/** A race-safe cancellation signal shared by one logical execution. */
public interface CancellationSignal {
    boolean isCancelled();

    Registration register(Runnable callback);

    interface Registration extends AutoCloseable {
        @Override void close();
    }

    static CancellationSignal none() {
        return NoneHolder.INSTANCE;
    }

    final class NoneHolder {
        private static final CancellationSignal INSTANCE = new CancellationSignal() {
            @Override public boolean isCancelled() { return false; }
            @Override public Registration register(Runnable callback) { return () -> { }; }
        };
        private NoneHolder() { }
    }
}
