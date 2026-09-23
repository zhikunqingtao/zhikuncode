package com.aicodeassistant.engine;

import com.aicodeassistant.llm.LlmProvider;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Uses the existing isolated engine fixture; no network provider is invoked. */
class RankingQueryReproTest {
    @Test void projectionBudgetExceptionIsCaughtAndReturnedAsQueryError() throws Exception {
        var fixture=new QueryEngineUnitTest();
        try(var mocks=MockitoAnnotations.openMocks(fixture)) {
            fixture.setUp();
            QueryEngine engine=(QueryEngine)ReflectionTestUtils.getField(fixture,"queryEngine");
            QueryConfig config=ReflectionTestUtils.invokeMethod(fixture,"buildConfig");
            QueryLoopState state=ReflectionTestUtils.invokeMethod(fixture,"buildState","synthetic user request");
            state.setHandoffOperationId("synthetic-merge");
            HandoffContextService handoff=mock(HandoffContextService.class);
            when(handoff.project(any(),anyString(),anyInt(),anyDouble(),anyBoolean()))
                    .thenThrow(new IllegalStateException("HANDOFF_CONTEXT_BUDGET_TOO_SMALL"));
            ReflectionTestUtils.setField(engine,"handoffContext",handoff);
            when(fixture.providerRegistry.getProvider(anyString())).thenReturn(mock(LlmProvider.class));
            QueryMessageHandler handler=mock(QueryMessageHandler.class);
            QueryEngine.QueryResult result=engine.execute(config,state,handler);
            assertThat(result.stopReason()).isEqualTo("error");
            assertThat(result.error()).isEqualTo("HANDOFF_CONTEXT_BUDGET_TOO_SMALL");
            verify(handler).onError(isA(IllegalStateException.class));
        }
    }
}
