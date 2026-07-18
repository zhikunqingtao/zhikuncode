package com.aicodeassistant.tool;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolInputTest {

    @Test
    void preservesJsonNullsWhileDefensivelyFreezingNestedCollections() {
        List<Object> nested = new ArrayList<>();
        nested.add(null);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("optional", null);
        source.put("nested", nested);

        ToolInput input = ToolInput.from(source);
        nested.add("late mutation");
        source.put("late", true);

        assertThat(input.getRawData()).containsEntry("optional", null).doesNotContainKey("late");
        @SuppressWarnings("unchecked")
        List<Object> frozenNested = (List<Object>) input.getRawData().get("nested");
        assertThat(frozenNested).containsExactly((Object) null);
        assertThatThrownBy(() -> input.getRawData().put("changed", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> frozenNested.add("changed"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
