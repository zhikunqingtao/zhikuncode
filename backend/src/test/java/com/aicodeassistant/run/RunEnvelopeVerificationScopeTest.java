package com.aicodeassistant.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RunEnvelopeVerificationScopeTest {

    @Test
    void unrecognizedDatabaseStatusReadsAsUnknownWithoutThrowing() {
        assertThat(RunEnvelope.VerificationStatus.fromDbValue("something_new"))
                .isEqualTo(RunEnvelope.VerificationStatus.UNKNOWN);
        assertThat(RunEnvelope.VerificationStatus.fromDbValue(null))
                .isEqualTo(RunEnvelope.VerificationStatus.UNKNOWN);
        assertThat(RunEnvelope.VerificationStatus.fromDbValue("verified"))
                .isEqualTo(RunEnvelope.VerificationStatus.VERIFIED);
        assertThat(RunEnvelope.VerificationStatus.fromDbValue("partial"))
                .isEqualTo(RunEnvelope.VerificationStatus.UNKNOWN);
    }

    @Test
    void serializesLimitedVerificationScope() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();

        assertThat(json.writeValueAsString(envelope(RunEnvelope.VerificationStatus.NOT_REQUESTED)))
                .contains("\"verificationScope\":\"artifact_manifest\"");
        assertThat(json.writeValueAsString(envelope(RunEnvelope.VerificationStatus.VERIFIED)))
                .contains("\"verificationScope\":\"artifact_manifest\"");
        assertThat(json.writeValueAsString(envelope(RunEnvelope.VerificationStatus.UNKNOWN)))
                .contains("\"verificationScope\":\"unknown\"");
    }

    private static RunEnvelope envelope(RunEnvelope.VerificationStatus status) {
        Instant now = Instant.parse("2026-08-12T01:00:00Z");
        return new RunEnvelope("root-current", "session-1", null, RunEnvelope.RunStatus.COMPLETED,
                "query", "model", null, now, now.plusSeconds(60), null,
                0, 0, 0, 0, null, now, now.plusSeconds(60), 1,
                RunEnvelope.RunExitReason.MODEL_FINISHED, null, status,
                RunEnvelope.UsageStatus.UNKNOWN, now.plusSeconds(60), null);
    }
}
