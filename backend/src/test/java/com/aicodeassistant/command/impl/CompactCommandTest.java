package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.CommandContext;
import com.aicodeassistant.command.CommandResult;
import com.aicodeassistant.engine.CompactService;
import com.aicodeassistant.engine.TokenCounter;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.session.SessionData;
import com.aicodeassistant.session.SessionManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompactCommandTest {
    static Stream<String> models() {
        return Stream.of(null, "", " \t ", "qwen3.8-flash");
    }

    @ParameterizedTest
    @MethodSource("models")
    void countingAndPreviewUseCurrentModelOrRegisteredDefault(String currentModel) {
        var compact = mock(CompactService.class);
        var counter = mock(TokenCounter.class);
        var providers = mock(LlmProviderRegistry.class);
        var sessions = mock(SessionManager.class);
        var session = mock(SessionData.class);
        var messages = List.<Message>of(new Message.UserMessage("goal", Instant.EPOCH,
                List.of(new ContentBlock.TextBlock("Keep current requirements")), null, null));
        when(sessions.loadSession("session")).thenReturn(Optional.of(session));
        when(session.messages()).thenReturn(messages);
        boolean fallback = currentModel == null || currentModel.isBlank();
        String expectedModel = fallback ? "deepseek-v4.1-flash" : currentModel;
        if (fallback) when(providers.getDefaultModel()).thenReturn(expectedModel);
        when(counter.estimateTokens(messages, expectedModel)).thenReturn(100);
        when(compact.compactForPreview(messages, expectedModel))
                .thenReturn(new CompactService.CompactResult(messages, 100, 50, 1, .5));

        var command = new CompactCommand(compact, counter, providers, sessions);
        var result = command.execute("", CommandContext.of("session", "/tmp", currentModel, null));

        assertEquals(CommandResult.ResultType.COMPACT, result.type());
        assertEquals(100, result.data().get("beforeTokens"));
        verify(counter).estimateTokens(messages, expectedModel);
        verify(compact).compactForPreview(messages, expectedModel);
        if (fallback) verify(providers).getDefaultModel();
        else verifyNoInteractions(providers);
    }
}
