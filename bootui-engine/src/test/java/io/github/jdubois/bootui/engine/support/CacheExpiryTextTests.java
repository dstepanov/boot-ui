package io.github.jdubois.bootui.engine.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Guards the wording the Cache panel shows for tier limits. The phrases are shared by the Spring and Quarkus
 * adapters precisely so the same configured policy reads the same way on every stack, so a change here is a
 * change to what three panels say.
 */
class CacheExpiryTextTests {

    @Test
    void phrasesCaffeineStylePolicies() {
        assertThat(CacheExpiryText.expireAfterWrite(Duration.ofMinutes(5))).isEqualTo("expire after write 5m");
        assertThat(CacheExpiryText.expireAfterAccess(Duration.ofSeconds(90))).isEqualTo("expire after access 1m 30s");
        assertThat(CacheExpiryText.refreshAfterWrite(Duration.ofHours(1))).isEqualTo("refresh after write 1h");
        assertThat(CacheExpiryText.expirePerEntry()).isEqualTo("expire per entry");
    }

    @Test
    void keepsAnImmediateExpiryVisibleInsteadOfDroppingIt() {
        // Caffeine reads a zero expiry as "expire immediately". Dropping it would render a cache that keeps
        // nothing as one with no expiry at all — the exact opposite of the truth.
        assertThat(CacheExpiryText.expireAfterWrite(Duration.ZERO)).isEqualTo("expire after write 0ms");
        assertThat(Durations.format(Duration.ZERO)).isEqualTo("0ms");
    }

    @Test
    void reportsNoPhraseForAnAbsentOrNegativeDuration() {
        assertThat(CacheExpiryText.expireAfterWrite(null)).isNull();
        assertThat(CacheExpiryText.expireAfterAccess(Duration.ofSeconds(-1))).isNull();
    }

    @Test
    void readsAZeroRedisTimeToLiveAsNoExpiry() {
        // Spring Data Redis gives zero the opposite meaning: an entry with no TTL never expires.
        assertThat(CacheExpiryText.timeToLive(Duration.ZERO, false)).isEqualTo(CacheExpiryText.NO_EXPIRY);
        assertThat(CacheExpiryText.timeToLive(null, false)).isEqualTo(CacheExpiryText.NO_EXPIRY);
        assertThat(CacheExpiryText.timeToLive(Duration.ofMinutes(10), false)).isEqualTo("time to live 10m");
        assertThat(CacheExpiryText.timeToLive(Duration.ofMinutes(10), true)).isEqualTo("time to idle 10m");
        assertThat(CacheExpiryText.timeToLiveComputedPerEntry(false)).isEqualTo("time to live computed per entry");
    }

    @Test
    void showsAnUnconvertibleConfiguredValueVerbatimRatherThanDroppingIt() {
        assertThat(CacheExpiryText.verbatim("expire after write", " 5M ")).isEqualTo("expire after write 5M");
        assertThat(CacheExpiryText.verbatim("expire after write", "  ")).isNull();
        assertThat(CacheExpiryText.verbatim("expire after write", null)).isNull();
    }

    @Test
    void joinsOnlyThePhrasesThatArePresent() {
        assertThat(CacheExpiryText.summary("expire after write 5m", null, "expire per entry"))
                .isEqualTo("expire after write 5m, expire per entry");
        assertThat(CacheExpiryText.summary(null, null)).isNull();
        assertThat(CacheExpiryText.summary()).isNull();
    }
}
