package com.aicodeassistant.llm;

import com.aicodeassistant.engine.AbortContext;
import com.aicodeassistant.engine.AbortReason;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LlmCallIsolationTest {
    @RepeatedTest(100)
    void registerAndCancelRaceNeverLosesCancellation() throws Exception {
        AbortContext context = new AbortContext();
        CountDownLatch invoked = new CountDownLatch(1);
        Thread register = Thread.ofVirtual().start(() -> context.register(invoked::countDown));
        Thread cancel = Thread.ofVirtual().start(() -> context.abort(AbortReason.USER_INTERRUPT));
        register.join();
        cancel.join();
        assertTrue(invoked.await(1, TimeUnit.SECONDS));
    }

    @Test
    void cancellingOneConcurrentCallDoesNotCancelTheOther() throws Exception {
        ConcurrentHashMap<String, FakeCall> active = new ConcurrentHashMap<>();
        AbortContext firstSignal = new AbortContext();
        AbortContext secondSignal = new AbortContext();
        FakeCall first = new FakeCall();
        FakeCall second = new FakeCall();
        try (AutoCloseable firstLease = LlmCallRegistration.register(active, "a", first, firstSignal, FakeCall::cancel);
             AutoCloseable secondLease = LlmCallRegistration.register(active, "b", second, secondSignal, FakeCall::cancel)) {
            firstSignal.abort(AbortReason.USER_INTERRUPT);
            assertTrue(first.cancelled);
            assertFalse(second.cancelled);
            assertSame(second, active.get("b"));
        }
        assertTrue(active.isEmpty());
    }

    private static final class FakeCall { private volatile boolean cancelled; void cancel() { cancelled = true; } }
}
