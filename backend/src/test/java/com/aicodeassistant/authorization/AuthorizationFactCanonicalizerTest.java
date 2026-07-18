package com.aicodeassistant.authorization;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationFactCanonicalizerTest {
    @Test
    void canonicalizesEverySetLikeFactWithStableTotalOrdering() {
        assertThat(AuthorizationFactCanonicalizer.effects(List.of(
                EffectClass.PROCESS, EffectClass.READ_RESOURCE, EffectClass.PROCESS)))
                .containsExactly(EffectClass.PROCESS, EffectClass.READ_RESOURCE);
        assertThat(AuthorizationFactCanonicalizer.resources(List.of(
                new ResourceRef("path", "b", false),
                new ResourceRef("path", "a", true),
                new ResourceRef("path", "a", false))))
                .containsExactly(
                        new ResourceRef("path", "a", false),
                        new ResourceRef("path", "a", true),
                        new ResourceRef("path", "b", false));
        assertThat(AuthorizationFactCanonicalizer.strings(List.of("z", "a", "z")))
                .containsExactly("a", "z");
    }
}
