package com.aicodeassistant.controller;
import com.aicodeassistant.service.ActivityRepository;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
class ActivityDecisionControllerTest {
    @Test void confirmsOnlyAnExistingActivityInTheRequestedSession() {
        var repository = mock(ActivityRepository.class);
        var controller = new ActivityController(repository);
        when(repository.updateDecisionForSession("s", "a", "approved")).thenReturn(true);
        var response = controller.updateDecision("s", "a", Map.of("decision", "approved"));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("sessionId", "s").containsEntry("decision", "approved");
        assertThat(controller.updateDecision("other", "a", Map.of("decision", "approved")).getStatusCode().value()).isEqualTo(404);
    }
    @Test void rejectsUnsupportedDecisionsWithoutWriting() {
        var repository = mock(ActivityRepository.class);
        assertThat(new ActivityController(repository).updateDecision("s", "a", Map.of("decision", "invalid")).getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(repository);
    }
}
