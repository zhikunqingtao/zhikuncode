package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.CommandContext;
import com.aicodeassistant.command.CommandResult;
import com.aicodeassistant.state.AppState;
import com.aicodeassistant.websocket.WebSocketController;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class PlanCommandTest {

    static Stream<Arguments> panelRequests() {
        return Stream.of(
                Arguments.of(null, true, "New Plan"),
                Arguments.of("", true, "New Plan"),
                Arguments.of("on", true, "New Plan"),
                Arguments.of("on Review changes", true, "Review changes"),
                Arguments.of("Review changes", true, "Review changes"),
                Arguments.of("off", false, null)
        );
    }

    @ParameterizedTest
    @MethodSource("panelRequests")
    void describesThePanelEventWithoutClaimingAPermissionChange(String args, boolean visible, String planName) {
        WebSocketController wsController = mock(WebSocketController.class);
        PlanCommand command = new PlanCommand(wsController);
        CommandContext context = CommandContext.of("test-session", "/tmp/workspace", "test-model",
                AppState.defaultState());

        CommandResult result = command.execute(args, context);

        assertEquals(CommandResult.ResultType.TEXT, result.type());
        assertTrue(result.value().contains(visible ? "Planning panel opened" : "Planning panel closed"));
        assertTrue(result.value().contains("Session permissions are unchanged"));
        assertTrue(command.getDescription().contains("session permissions are unchanged"));
        Map<String, Object> expected = visible
                ? Map.of("isPlanMode", true, "planName", planName, "planOverview", "")
                : Map.of("isPlanMode", false);
        verify(wsController).sendPlanUpdate("test-session", expected);
        verifyNoMoreInteractions(wsController);
    }
}
