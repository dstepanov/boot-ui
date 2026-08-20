package io.github.jdubois.bootui.sample.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

class NativeHintsConfigurationTests {

    @Test
    void registersCaffeineJCacheConfiguration() {
        RuntimeHints hints = new RuntimeHints();
        new NativeHintsConfiguration.SampleRuntimeHints()
                .registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.resource().forResource("application.conf"))
                .accepts(hints);
    }

    @Test
    void registersJCacheRegionFactoryPublicConstructor() {
        RuntimeHints hints = new RuntimeHints();
        new NativeHintsConfiguration.SampleRuntimeHints()
                .registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(TypeReference.of("org.hibernate.cache.jcache.internal.JCacheRegionFactory"))
                        .withMemberCategory(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS))
                .accepts(hints);
    }
}
