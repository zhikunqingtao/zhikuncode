package com.aicodeassistant.engine;

import com.aicodeassistant.authorization.AuthorizationSubjectResolver;
import com.aicodeassistant.session.merge.*;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.impl.HandoffReadTool;
import com.aicodeassistant.tool.impl.ImageResultExternalizer;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HandoffContextServiceTest {
    final MergeProgressRepository repository=mock(MergeProgressRepository.class);
    final HandoffReadService reads=mock(HandoffReadService.class);
    final AuthorizationSubjectResolver subjects=mock(AuthorizationSubjectResolver.class);
    final TokenCounter tokens=mock(TokenCounter.class);
    final HandoffContextService service=new HandoffContextService(repository,reads,subjects,tokens,mock(ImageResultExternalizer.class));
    final QueryConfig config=QueryConfig.withDefaults("model","system",List.of(),List.of(),2048,32000,null,10,"test");
    final QueryLoopState state=new QueryLoopState(List.of(),ToolUseContext.of("/workspace","E"));
    final Map<String,Object> metadata=Map.of(HandoffContextService.METADATA_KEY,"operation");

    @Test void tooSmallEntryBudgetReturnsTypedCapacityFailureWithoutMutatingHistory() throws Exception {
        when(repository.binding("E")).thenReturn(Optional.of(mock(MergeHandoffData.Ledger.class)));
        when(reads.brief("E")).thenReturn("交接资料");
        assertThatThrownBy(() -> service.project(state.getToolUseContext(),"model",100,2))
                .isInstanceOfSatisfying(HandoffContextService.CapacityException.class,error -> {
                    assertThat(error.getHttpStatus()).isEqualTo(413);
                    assertThat(error.getErrorType()).isEqualTo("CONTEXT_BUDGET_EXCEEDED");
                    assertThat(error.isRetryable()).isFalse();
                });
        assertThat(state.getMessages()).isEmpty();
    }

    @Test void ordinaryRequestRetainsTheExactConfigurationWithoutCallingMergeServices() {
        var configured=service.configure(config,state,Map.of("ordinary","value"),null,null);
        assertThat(configured).isSameAs(config);
        assertThat(state.getHandoffOperationId()).isNull();
        verifyNoInteractions(repository,reads,subjects,tokens);
        assertThat(HandoffReadTool.class.isAnnotationPresent(Component.class)).isFalse();
    }
    @Test void mergedRequestAddsTheToolOnlyToItsOwnConfiguration() {
        var binding=mock(MergeHandoffData.Ledger.class);
        when(binding.operationId()).thenReturn("operation"); when(reads.binding("E")).thenReturn(binding);
        var configured=service.configure(config,state,metadata,null,null);
        assertThat(configured.tools()).hasSize(1).allMatch(HandoffReadTool.class::isInstance);
        assertThat(configured.toolDefinitions()).hasSize(1);
        assertThat(config.tools()).isEmpty(); assertThat(config.toolDefinitions()).isEmpty();
        assertThat(configured.systemPrompt()).isEqualTo(config.systemPrompt());
        assertThat(state.getHandoffOperationId()).isEqualTo("operation");
    }
    @Test void userToolRestrictionIsRespectedAndProjectionExplainsTheMissingTool() {
        var binding=mock(MergeHandoffData.Ledger.class);
        when(binding.operationId()).thenReturn("operation"); when(reads.binding("E")).thenReturn(binding);
        when(repository.binding("E")).thenReturn(Optional.of(binding));
        var configured=service.configure(config,state,metadata,null,List.of("HandoffRead"));
        assertThat(configured).isSameAs(config);
        var projection=service.project(state.getToolUseContext(),"model",32000,2,false);
        assertThat(projection.messages().toString()).contains("工具当前不可用","不能假称已读取");
        verify(reads).binding("E");
        verifyNoMoreInteractions(reads);
    }
    @Test void explicitAllowListDoesNotGainAnUnrequestedTool() {
        var binding=mock(MergeHandoffData.Ledger.class);
        when(binding.operationId()).thenReturn("operation"); when(reads.binding("E")).thenReturn(binding);
        assertThat(service.configure(config,state,metadata,List.of("FileRead"),null)).isSameAs(config);
        assertThat(state.getHandoffOperationId()).isEqualTo("operation");
    }
    @Test void metadataCannotSelectAnotherSessionsPackage() {
        var binding=mock(MergeHandoffData.Ledger.class);
        when(binding.operationId()).thenReturn("another-operation"); when(reads.binding("E")).thenReturn(binding);
        assertThatThrownBy(() -> service.configure(config,state,metadata,null,null))
                .isInstanceOf(IllegalStateException.class).hasMessage("HANDOFF_BINDING_MISMATCH");
        assertThat(state.getHandoffOperationId()).isNull();
    }
}
