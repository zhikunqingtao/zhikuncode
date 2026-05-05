package com.aicodeassistant.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 QueryLoopState 的 promptTooLongWithheld 状态流转
 */
class QueryEngineWithholdTest {

    private QueryLoopState state;

    @BeforeEach
    void setUp() {
        state = new QueryLoopState(new ArrayList<>(), null);
    }

    @Test
    @DisplayName("初始状态：promptTooLongWithheld 为 false")
    void initialState_shouldBeFalse() {
        assertFalse(state.isPromptTooLongWithheld());
    }

    @Test
    @DisplayName("设置扣留状态为true")
    void setWithheld_shouldBeTrue() {
        state.setPromptTooLongWithheld(true);
        assertTrue(state.isPromptTooLongWithheld());
    }

    @Test
    @DisplayName("恢复成功后重置为false")
    void recoverySuccess_shouldResetToFalse() {
        state.setPromptTooLongWithheld(true);
        assertTrue(state.isPromptTooLongWithheld());

        // 模拟恢复成功
        state.setPromptTooLongWithheld(false);
        assertFalse(state.isPromptTooLongWithheld());
    }

    @Test
    @DisplayName("完整生命周期：扣留 → 恢复 → 释放")
    void fullLifecycle_withhold_recover_release() {
        // 初始状态
        assertFalse(state.isPromptTooLongWithheld());

        // 进入扣留
        state.setPromptTooLongWithheld(true);
        assertTrue(state.isPromptTooLongWithheld());

        // 恢复成功释放
        state.setPromptTooLongWithheld(false);
        state.clearWithheldErrors();
        assertFalse(state.isPromptTooLongWithheld());
        assertTrue(state.getWithheldErrors().isEmpty());
    }

    @Test
    @DisplayName("incrementalCollapseNeeded 初始状态为 false")
    void incrementalCollapseNeeded_initialState() {
        assertFalse(state.isIncrementalCollapseNeeded());
    }

    @Test
    @DisplayName("incrementalCollapseNeeded 可正确设置和重置")
    void incrementalCollapseNeeded_setAndReset() {
        state.setIncrementalCollapseNeeded(true);
        assertTrue(state.isIncrementalCollapseNeeded());

        state.setIncrementalCollapseNeeded(false);
        assertFalse(state.isIncrementalCollapseNeeded());
    }
}
