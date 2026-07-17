package com.aicodeassistant.tool.bash;

import com.aicodeassistant.security.CommandBlacklistService;
import com.aicodeassistant.state.AppStateStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BashSecurityAnalyzerEnvironmentTest {
    private final BashSecurityAnalyzer analyzer = new BashSecurityAnalyzer(
            mock(PathValidator.class), mock(AppStateStore.class),
            mock(CommandBlacklistService.class));

    @Test
    void astScopeRecognizesShellLocalDefinitions() {
        assertLocal("LOG=build.log; grep error \"$LOG\"");
        assertLocal("for p in a b; do echo \"$p\"; done");
        assertLocal("read value; printf '%s' \"$value\"");
        assertLocal("printf '%s' \"$1\"");
        assertLocal("f(){ local item=ok; echo \"$item\"; }; f");
    }

    @Test
    void inheritedUnknownAndSensitiveVariablesAreClassified() {
        var unknown = analyzer.analyzeEnvironmentReferences("echo \"$CUSTOM_HOME\"");
        assertThat(unknown.inheritedReferences()).containsExactly("CUSTOM_HOME");
        assertThat(unknown.sensitiveInheritedReferences()).isEmpty();

        var sensitive = analyzer.analyzeEnvironmentReferences("echo \"$OPENAI_API_KEY\"");
        assertThat(sensitive.sensitiveInheritedReferences()).containsExactly("OPENAI_API_KEY");

        var allowed = analyzer.analyzeEnvironmentReferences("echo \"$PATH\"");
        assertThat(allowed.inheritedReferences()).containsExactly("PATH");
        assertThat(analyzer.isAllowedInheritedEnvironmentReference("PATH")).isTrue();
    }

    @Test
    void assignmentRightHandSideAndDynamicExpansionFailClosed() {
        var inherited = analyzer.analyzeEnvironmentReferences("SECRET=$OPENAI_API_KEY; echo \"$SECRET\"");
        assertThat(inherited.sensitiveInheritedReferences()).contains("OPENAI_API_KEY");
        assertThat(inherited.inheritedReferences()).doesNotContain("SECRET");

        assertThat(analyzer.analyzeEnvironmentReferences("echo \"${!name}\"")
                .requiresConservativeAsk()).isTrue();
        assertThat(analyzer.analyzeEnvironmentReferences("eval \"$COMMAND\"")
                .requiresConservativeAsk()).isTrue();
    }

    private void assertLocal(String command) {
        var result = analyzer.analyzeEnvironmentReferences(command);
        assertThat(result.requiresConservativeAsk()).as(command).isFalse();
        assertThat(result.inheritedReferences()).as(command).isEmpty();
    }
}
