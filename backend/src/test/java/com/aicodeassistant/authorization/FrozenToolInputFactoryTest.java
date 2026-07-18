package com.aicodeassistant.authorization;

import com.aicodeassistant.tool.ToolInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrozenToolInputFactoryTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void canonicalHashIsOrderStableAndExecutionUsesFrozenBytes() {
        FrozenToolInputFactory factory = new FrozenToolInputFactory(json, 1024, 4096);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("z", 1); first.put("a", Map.of("b", 2, "a", 1));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", Map.of("a", 1, "b", 2)); second.put("z", 1);

        try (FrozenToolInput one = factory.freeze("Demo", ToolInput.from(first));
             FrozenToolInput two = factory.freeze("Demo", ToolInput.from(second))) {
            assertThat(one.inputHash()).isEqualTo(two.inputHash());
            first.put("z", 999);
            assertThat(one.toToolInput(json).getInt("z")).isEqualTo(1);
        }
        assertThat(factory.inflightBytes()).isZero();
    }

    @Test
    void conservativeCanonicalizationDoesNotMergeNumbersOrUnicodeForms() {
        FrozenToolInputFactory factory = new FrozenToolInputFactory(json, 1024, 4096);
        try (FrozenToolInput integer = factory.freeze("Demo", ToolInput.from(Map.of("v", 1)));
             FrozenToolInput decimal = factory.freeze("Demo", ToolInput.from(Map.of("v", 1.0)));
             FrozenToolInput nfc = factory.freeze("Demo", ToolInput.from(Map.of("v", Normalizer.normalize("é", Normalizer.Form.NFC))));
             FrozenToolInput nfd = factory.freeze("Demo", ToolInput.from(Map.of("v", Normalizer.normalize("é", Normalizer.Form.NFD))))) {
            assertThat(integer.inputHash()).isNotEqualTo(decimal.inputHash());
            assertThat(nfc.inputHash()).isNotEqualTo(nfd.inputHash());
        }
    }

    @Test
    void sizeAndInflightBudgetsFailWithoutLeakingPermits() {
        FrozenToolInputFactory tiny = new FrozenToolInputFactory(json, 32, 64);
        assertThatThrownBy(() -> tiny.freeze("Demo", ToolInput.from(Map.of("v", "x".repeat(100)))))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Canonical tool input exceeds");
        assertThat(tiny.inflightBytes()).isZero();

        FrozenToolInputFactory weighted = new FrozenToolInputFactory(json, 64, 64);
        try (FrozenToolInput held = weighted.freeze("Demo", ToolInput.from(Map.of("v", "held")))) {
            assertThatThrownBy(() -> weighted.freeze("Demo", ToolInput.from(Map.of("v", "next"))))
                    .isInstanceOf(AuthorizationException.class);
        }
        assertThat(weighted.inflightBytes()).isZero();
    }
}
