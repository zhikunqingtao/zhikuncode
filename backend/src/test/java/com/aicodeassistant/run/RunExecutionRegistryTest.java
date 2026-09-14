package com.aicodeassistant.run;

import com.aicodeassistant.engine.AbortContext;
import com.aicodeassistant.engine.AbortReason;
import com.aicodeassistant.websocket.ClientMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunExecutionRegistryTest {

    @Test
    void duplicateRequestIdIsIdempotentAndClaimedOnce() {
        RunExecutionRegistry registry = new RunExecutionRegistry();
        registry.register("run", "session", new AbortContext());
        String requestId = UUID.randomUUID().toString();

        var first = registry.offerInputForSession("session", requestId, "first");
        var duplicate = registry.offerInputForSession("session", requestId, "different");

        assertThat(first.accepted()).isTrue();
        assertThat(duplicate.accepted()).isTrue();
        assertThat(duplicate.receipt()).isEqualTo(first.receipt());
        List<RunExecutionRegistry.InputApplication> applications =
                registry.claimInputs("run", 10);
        assertThat(applications).hasSize(1);
        assertThat(applications.getFirst().input().text()).isEqualTo("first");
        applications.getFirst().applyIfAccepting(
                System.currentTimeMillis(), () -> { });
        assertThat(registry.claimInputs("run", 10)).isEmpty();
    }

    @Test
    void concurrentDuplicateOffersStillProduceOneApplication() throws Exception {
        RunExecutionRegistry registry = new RunExecutionRegistry();
        registry.register("run", "session", new AbortContext());
        String requestId = UUID.randomUUID().toString();
        int callers = 20;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<RunExecutionRegistry.InputOfferResult> results =
                java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            Thread thread = Thread.ofVirtual().start(() -> {
                ready.countDown();
                try {
                    start.await();
                    results.add(registry.offerInputForSession(
                            "session", requestId, "steer"));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(thread);
        }
        ready.await();
        start.countDown();
        for (Thread thread : threads) thread.join();

        assertThat(results).hasSize(callers).allMatch(
                RunExecutionRegistry.InputOfferResult::accepted);
        assertThat(registry.claimInputs("run", 10)).hasSize(1);
    }

    @Test
    void pendingQueueLimitIsEnforcedWithoutDroppingAcceptedInputs() {
        RunExecutionRegistry registry = new RunExecutionRegistry();
        registry.register("run", "session", new AbortContext());
        for (int i = 0; i < 10; i++) {
            assertThat(registry.offerInputForSession(
                    "session", UUID.randomUUID().toString(), "input-" + i)
                    .accepted()).isTrue();
        }

        var overflow = registry.offerInputForSession(
                "session", UUID.randomUUID().toString(), "overflow");

        assertThat(overflow.accepted()).isFalse();
        assertThat(overflow.receipt().rejectionCode()).isEqualTo("QUEUE_FULL");
        assertThat(registry.claimInputs("run", 10)).hasSize(10);
    }

    @Test
    void completionClaimOrSealIsAtomicWithNewOffers() {
        RunExecutionRegistry registry = new RunExecutionRegistry();
        registry.register("run", "session", new AbortContext());

        var decision = registry.claimOrSealInputs("run", 10);
        var late = registry.offerInputForSession(
                "session", UUID.randomUUID().toString(), "too late");

        assertThat(decision.sealed()).isTrue();
        assertThat(decision.applications()).isEmpty();
        assertThat(late.accepted()).isFalse();
        assertThat(late.receipt().rejectionCode())
                .isEqualTo("RUN_NOT_ACCEPTING_INPUT");
    }

    @Test
    void concurrentOfferAndCompletionSealNeverLeaveAnAcceptedInputQueued()
            throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            RunExecutionRegistry registry = new RunExecutionRegistry();
            registry.register("run", "session", new AbortContext());
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<RunExecutionRegistry.InputOfferResult> offered =
                    new AtomicReference<>();
            AtomicReference<RunExecutionRegistry.CompletionInputDecision> decision =
                    new AtomicReference<>();
            String requestId = UUID.randomUUID().toString();
            Thread offerThread = Thread.ofVirtual().start(() -> {
                await(start);
                offered.set(registry.offerInputForSession(
                        "session", requestId, "steer"));
            });
            Thread sealThread = Thread.ofVirtual().start(() -> {
                await(start);
                decision.set(registry.claimOrSealInputs("run", 1));
            });
            start.countDown();
            offerThread.join();
            sealThread.join();

            if (offered.get().accepted()) {
                assertThat(decision.get().applications()).hasSize(1);
                decision.get().applications().getFirst()
                        .applyIfAccepting(
                                System.currentTimeMillis(), () -> { });
            } else {
                assertThat(decision.get().sealed()).isTrue();
                assertThat(decision.get().applications()).isEmpty();
            }
            registry.unregister("run");
        }
    }

    @Test
    void closingUnsettledApplicationRejectsItAndReleasesRunWork() {
        RunExecutionRegistry registry = new RunExecutionRegistry();
        registry.register("run", "session", new AbortContext());
        String requestId = UUID.randomUUID().toString();
        registry.offerInputForSession("session", requestId, "steer");
        RunExecutionRegistry.InputApplication application =
                registry.claimInputs("run", 1).getFirst();

        application.close();
        registry.unregister("run");

        assertThat(registry.isRegistered("run")).isFalse();
    }

    @Test
    void abortBeforeApplicationRejectsWithoutApplyingMessage() {
        RunExecutionRegistry registry = new RunExecutionRegistry();
        AbortContext cancellation = new AbortContext();
        registry.register("run", "session", cancellation);
        String requestId = UUID.randomUUID().toString();
        registry.offerInputForSession("session", requestId, "steer");
        RunExecutionRegistry.InputApplication application =
                registry.claimInputs("run", 1).getFirst();
        AtomicBoolean messageApplied = new AtomicBoolean();

        registry.abortRun("run", AbortReason.USER_INTERRUPT);
        var receipt = application.applyIfAccepting(
                System.currentTimeMillis(),
                () -> messageApplied.set(true));

        assertThat(receipt.state())
                .isEqualTo(RunExecutionRegistry.InputState.REJECTED);
        assertThat(receipt.rejectionCode())
                .isEqualTo("RUN_NOT_ACCEPTING_INPUT");
        assertThat(messageApplied).isFalse();
    }

    @Test
    void applicationBeforeAbortRemainsApplied() {
        RunExecutionRegistry registry = new RunExecutionRegistry();
        registry.register("run", "session", new AbortContext());
        String requestId = UUID.randomUUID().toString();
        registry.offerInputForSession("session", requestId, "steer");
        RunExecutionRegistry.InputApplication application =
                registry.claimInputs("run", 1).getFirst();
        AtomicBoolean messageApplied = new AtomicBoolean();

        var receipt = application.applyIfAccepting(
                System.currentTimeMillis(),
                () -> messageApplied.set(true));
        registry.abortRun("run", AbortReason.USER_INTERRUPT);

        assertThat(receipt.state())
                .isEqualTo(RunExecutionRegistry.InputState.APPLIED);
        assertThat(messageApplied).isTrue();
    }

    @Test
    void concurrentAbortAndApplicationHaveConsistentTerminalState()
            throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            RunExecutionRegistry registry = new RunExecutionRegistry();
            registry.register("run", "session", new AbortContext());
            String requestId = UUID.randomUUID().toString();
            registry.offerInputForSession("session", requestId, "steer");
            RunExecutionRegistry.InputApplication application =
                    registry.claimInputs("run", 1).getFirst();
            AtomicBoolean messageApplied = new AtomicBoolean();
            AtomicReference<RunExecutionRegistry.InputReceipt> receipt =
                    new AtomicReference<>();
            CountDownLatch start = new CountDownLatch(1);

            Thread applyThread = Thread.ofVirtual().start(() -> {
                await(start);
                receipt.set(application.applyIfAccepting(
                        System.currentTimeMillis(),
                        () -> messageApplied.set(true)));
            });
            Thread abortThread = Thread.ofVirtual().start(() -> {
                await(start);
                registry.abortRun("run", AbortReason.USER_INTERRUPT);
            });
            start.countDown();
            applyThread.join();
            abortThread.join();

            assertThat(receipt.get()).isNotNull();
            if (receipt.get().state()
                    == RunExecutionRegistry.InputState.APPLIED) {
                assertThat(messageApplied).isTrue();
            } else {
                assertThat(receipt.get().state())
                        .isEqualTo(RunExecutionRegistry.InputState.REJECTED);
                assertThat(receipt.get().rejectionCode())
                        .isEqualTo("RUN_NOT_ACCEPTING_INPUT");
                assertThat(messageApplied).isFalse();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    @Test
    void unregisterRemovesImmediatelyWhenNoWorkIsActive() {
        RunExecutionRegistry registry = new RunExecutionRegistry();
        registry.register("run", "session", new AbortContext());

        registry.unregister("run");

        assertThat(registry.isRegistered("run")).isFalse();
        assertThat(registry.activeRunForSession("session")).isEmpty();
    }

    @Test
    void lateLeaseReleaseCompletesDeferredUnregister() throws Exception {
        RunExecutionRegistry registry = new RunExecutionRegistry();
        registry.register("run", "session", new AbortContext());
        RunExecutionRegistry.WorkLease lease = registry.acquireWork("run", "tool", "tool-1");

        Thread unregister = Thread.ofVirtual().start(() -> registry.unregister("run"));
        unregister.join(3_000);
        assertThat(unregister.isAlive()).isFalse();
        assertThat(registry.isRegistered("run")).isTrue();
        assertThatThrownBy(() -> registry.acquireWork("run", "tool", "tool-2"))
                .isInstanceOf(RunExecutionRegistry.WorkRejectedException.class)
                .hasMessage("RUN_WORK_ADMISSION_CLOSED");

        lease.close();

        assertThat(registry.isRegistered("run")).isFalse();
        assertThat(registry.activeRunForSession("session")).isEmpty();
    }

    @Test
    void nullMetaValueIsRejectedBeforeAnyStateMutation() throws Exception {
        // Entry-point reality check: Jackson maps {"steering": null} to a
        // Java Map containing key "steering" with a null value.
        ClientMessage.RunInputPayload payload = new ObjectMapper().readValue(
                "{\"requestId\":null,\"text\":\"steer\",\"meta\":{\"steering\":null}}",
                ClientMessage.RunInputPayload.class);
        assertThat(payload.meta()).containsKey("steering");
        assertThat(payload.meta().get("steering")).isNull();

        RunExecutionRegistry registry = new RunExecutionRegistry();
        registry.register("run", "session", new AbortContext());
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> meta = new HashMap<>();
        meta.put("steering", null);

        // Null metadata values are rejected with IllegalArgumentException
        // before any receipt is written or the input is enqueued.
        assertThatThrownBy(() -> registry.offerInputForSession(
                "session", requestId, "steer", meta))
                .isInstanceOf(IllegalArgumentException.class);

        // The failed offer enqueued nothing.
        assertThat(registry.claimInputs("run", 10)).isEmpty();

        // No half-persisted receipt short-circuits the retry: the same
        // requestId is accepted fresh, returns QUEUED, and is claimable.
        var retry = registry.offerInputForSession(
                "session", requestId, "steer", Map.of("steering", true));
        assertThat(retry.accepted()).isTrue();
        assertThat(retry.receipt().state())
                .isEqualTo(RunExecutionRegistry.InputState.QUEUED);
        List<RunExecutionRegistry.InputApplication> applications =
                registry.claimInputs("run", 10);
        assertThat(applications).hasSize(1);
        assertThat(applications.getFirst().input().meta())
                .containsEntry("steering", true);
    }

    @Test
    void nullMetaKeyIsRejectedBeforeAnyStateMutation() {
        RunExecutionRegistry registry = new RunExecutionRegistry();
        registry.register("run", "session", new AbortContext());
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> meta = new HashMap<>();
        meta.put(null, "steering");

        assertThatThrownBy(() -> registry.offerInputForSession(
                "session", requestId, "steer", meta))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(registry.claimInputs("run", 10)).isEmpty();

        // The requestId was not burned: a valid retry is queued and claimable.
        var retry = registry.offerInputForSession(
                "session", requestId, "steer");
        assertThat(retry.accepted()).isTrue();
        assertThat(retry.receipt().state())
                .isEqualTo(RunExecutionRegistry.InputState.QUEUED);
        assertThat(registry.claimInputs("run", 10)).hasSize(1);
    }

    @Test
    void validMetaIsSnapshottedAndDeliveredWithClaimedInput() {
        RunExecutionRegistry registry = new RunExecutionRegistry();
        registry.register("run", "session", new AbortContext());
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> meta = new HashMap<>();
        meta.put("steering", true);
        meta.put("source", "client");

        var offer = registry.offerInputForSession(
                "session", requestId, "steer", meta);
        // Later mutation of the caller's map must not leak into the snapshot.
        meta.put("steering", false);

        assertThat(offer.accepted()).isTrue();
        List<RunExecutionRegistry.InputApplication> applications =
                registry.claimInputs("run", 10);
        assertThat(applications).hasSize(1);
        assertThat(applications.getFirst().input().meta())
                .containsEntry("steering", true)
                .containsEntry("source", "client");
        var receipt = applications.getFirst().applyIfAccepting(
                System.currentTimeMillis(), () -> { });
        assertThat(receipt.state())
                .isEqualTo(RunExecutionRegistry.InputState.APPLIED);
    }

    @Test
    void emptyMetaMapIsAcceptedAndDeliveredAsNoMeta() {
        RunExecutionRegistry registry = new RunExecutionRegistry();
        registry.register("run", "session", new AbortContext());
        String requestId = UUID.randomUUID().toString();

        var offer = registry.offerInputForSession(
                "session", requestId, "steer", Map.of());

        assertThat(offer.accepted()).isTrue();
        assertThat(offer.receipt().state())
                .isEqualTo(RunExecutionRegistry.InputState.QUEUED);
        List<RunExecutionRegistry.InputApplication> applications =
                registry.claimInputs("run", 10);
        assertThat(applications).hasSize(1);
        // Empty metadata is normalized to "no metadata", as before.
        assertThat(applications.getFirst().input().meta()).isNull();
    }
}
