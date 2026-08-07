package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.ValueExposure;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuarkusExposurePolicyTest {

    @Test
    void failsClosedWhenExposureSettingsAreMissing() {
        QuarkusExposurePolicy policy = new QuarkusExposurePolicy(StubConfig.empty());

        assertThat(policy.valueExposure()).isEqualTo(ValueExposure.MASKED);
        assertThat(policy.maskSecrets()).isTrue();
    }

    @Test
    void readsLiveExposureSettings() {
        StubConfig config = new StubConfig(Map.of(
                QuarkusExposurePolicy.EXPOSE_VALUES_KEY, " full ",
                QuarkusExposurePolicy.MASK_SECRETS_KEY, "false"));
        QuarkusExposurePolicy policy = new QuarkusExposurePolicy(config);

        assertThat(policy.valueExposure()).isEqualTo(ValueExposure.FULL);
        assertThat(policy.maskSecrets()).isFalse();
    }

    @Test
    void failsClosedForBlankOrInvalidExposureModes() {
        assertThat(new QuarkusExposurePolicy(new StubConfig(Map.of(QuarkusExposurePolicy.EXPOSE_VALUES_KEY, "  ")))
                        .valueExposure())
                .isEqualTo(ValueExposure.MASKED);
        assertThat(new QuarkusExposurePolicy(new StubConfig(Map.of(QuarkusExposurePolicy.EXPOSE_VALUES_KEY, "invalid")))
                        .valueExposure())
                .isEqualTo(ValueExposure.MASKED);
    }
}
