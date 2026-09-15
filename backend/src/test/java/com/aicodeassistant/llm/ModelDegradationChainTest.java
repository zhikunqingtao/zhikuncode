package com.aicodeassistant.llm;

import com.aicodeassistant.engine.ModelTierService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ModelDegradationChainTest {
    private static final String BAILIAN = "deepseek-v4.1-flash";

    @Test
    void otherModelsFallBackToBailianWithoutSelfFallback() {
        var chain = new ModelDegradationChain();
        for (String model : List.of("claude-sonnet-4-6", "qwen3.8-max-0902")) {
            assertEquals(BAILIAN, chain.getNextFallback(model, 0).orElseThrow());
        }
        assertEquals("deepseek-flash", chain.getNextFallback(BAILIAN, 0).orElseThrow());
        assertFalse(chain.getDegradationChain(BAILIAN).contains(BAILIAN));
        assertFalse(chain.hasFallback("qwen3.7-plus", 0));
        assertTrue(chain.getNextFallback(BAILIAN, chain.getMaxDegradationDepth()).isEmpty());
    }

    @Test
    void cooldownSkipsBailianWhenItIsTheFailingModel() {
        var tiers = new ModelTierService();
        var chain = List.of(BAILIAN, "qwen3.8-max-0902", "qwen-turbo");
        tiers.triggerCooldown("qwen3.8-max-0902", 0, "test");
        assertEquals(BAILIAN, tiers.resolveModel("qwen3.8-max-0902", chain));
        tiers.triggerCooldown(BAILIAN, 0, "test");
        assertEquals("qwen-turbo", tiers.resolveModel(BAILIAN, chain));
    }
}
