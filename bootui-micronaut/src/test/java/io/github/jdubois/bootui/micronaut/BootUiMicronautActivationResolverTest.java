package io.github.jdubois.bootui.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.context.env.PropertySource;
import io.micronaut.context.env.PropertySourcePropertyResolver;
import io.micronaut.core.value.PropertyResolver;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins the activation decision, which is what keeps the console dark outside development. The rules mirror
 * the Spring adapter's {@code BootUiActivationCondition} with Micronaut environments in place of profiles.
 */
class BootUiMicronautActivationResolverTest {

    @Test
    void isEnabledByADefaultEnabledEnvironment() {
        assertThat(resolve(Map.of(), Set.of("dev")).enabled()).isTrue();
        assertThat(resolve(Map.of(), Set.of("local")).enabled()).isTrue();
        assertThat(resolve(Map.of(), Set.of("test")).enabled()).isTrue();
    }

    @Test
    void failsClosedWhenNoEnabledEnvironmentIsActive() {
        BootUiMicronautActivation activation = resolve(Map.of(), Set.of("cloud"));

        assertThat(activation.enabled()).isFalse();
        assertThat(activation.reason()).contains("bootui.enabled-environments");
    }

    @Test
    void isDisabledByAProductionEnvironmentEvenWhenAnEnabledOneIsAlsoActive() {
        BootUiMicronautActivation activation = resolve(Map.of(), Set.of("dev", "prod"));

        assertThat(activation.enabled()).isFalse();
        assertThat(activation.reason()).contains("'prod'");
    }

    @Test
    void isForcedOnOverAProductionEnvironmentButWarnsAboutIt() {
        BootUiMicronautActivation activation = resolve(Map.of("bootui.enabled", "ON"), Set.of("prod"));

        assertThat(activation.enabled()).isTrue();
        assertThat(activation.warnings()).hasSize(1);
        assertThat(activation.warnings().get(0)).contains("forces it on");
    }

    @Test
    void mapsTheBooleanFormsYamlParsesOntoTheThreeStateSwitch() {
        assertThat(resolve(Map.of("bootui.enabled", "true"), Set.of("cloud")).enabled())
                .isTrue();
        assertThat(resolve(Map.of("bootui.enabled", "false"), Set.of("dev")).enabled())
                .isFalse();
    }

    @Test
    void failsClosedOnAnInvalidSwitchValue() {
        BootUiMicronautActivation activation = resolve(Map.of("bootui.enabled", "maybe"), Set.of("dev"));

        assertThat(activation.enabled()).isFalse();
        assertThat(activation.reason()).contains("invalid");
    }

    @Test
    void honoursCustomEnvironmentLists() {
        assertThat(resolve(Map.of("bootui.enabled-environments", List.of("sandbox")), Set.of("sandbox"))
                        .enabled())
                .isTrue();
        assertThat(resolve(Map.of("bootui.enabled-environments", "sandbox,dev"), Set.of("dev"))
                        .enabled())
                .isTrue();
        assertThat(resolve(Map.of("bootui.disabled-environments", List.of("staging")), Set.of("dev", "staging"))
                        .enabled())
                .isFalse();
    }

    private static BootUiMicronautActivation resolve(Map<String, Object> properties, Set<String> environments) {
        PropertyResolver config = new PropertySourcePropertyResolver(PropertySource.of("test", properties));
        return BootUiMicronautActivationResolver.resolve(config, environments);
    }
}
