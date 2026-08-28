package io.github.jdubois.bootui.engine.github;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.github.GitHubTokenProvider.Token;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Resolution order and hygiene for the GitHub credential the Repository panel authenticates with.
 *
 * <p>The environment lookup is injected, so these tests never depend on the developer's own shell or on the
 * {@code gh} CLI being installed. The {@code gh auth token} fallback is only asserted through the "no
 * environment token" path, which must never surface an environment-sourced token.</p>
 */
class DefaultGitHubTokenProviderTests {

    private static DefaultGitHubTokenProvider providerWith(String... environmentPairs) {
        Map<String, String> environment = new LinkedHashMap<>();
        for (int i = 0; i < environmentPairs.length; i += 2) {
            environment.put(environmentPairs[i], environmentPairs[i + 1]);
        }
        return new DefaultGitHubTokenProvider(environment);
    }

    @Test
    void githubTokenIsPreferredOverGhToken() {
        Token token =
                providerWith("GITHUB_TOKEN", "primary", "GH_TOKEN", "secondary").token(Duration.ofSeconds(1));

        assertThat(token).isNotNull();
        assertThat(token.value()).isEqualTo("primary");
        assertThat(token.source()).isEqualTo("GITHUB_TOKEN");
    }

    @Test
    void ghTokenIsUsedWhenGithubTokenIsAbsent() {
        Token token = providerWith("GH_TOKEN", "secondary").token(Duration.ofSeconds(1));

        assertThat(token).isNotNull();
        assertThat(token.value()).isEqualTo("secondary");
        assertThat(token.source()).isEqualTo("GH_TOKEN");
    }

    @Test
    void surroundingWhitespaceIsStrippedFromTheEnvironmentValue() {
        Token token = providerWith("GITHUB_TOKEN", "  padded\n").token(Duration.ofSeconds(1));

        assertThat(token).isNotNull();
        assertThat(token.value()).isEqualTo("padded");
    }

    @Test
    void aBlankEnvironmentTokenIsIgnoredInFavourOfTheNextSource() {
        Token token =
                providerWith("GITHUB_TOKEN", "   ", "GH_TOKEN", "secondary").token(Duration.ofSeconds(1));

        assertThat(token).isNotNull();
        assertThat(token.value()).isEqualTo("secondary");
        assertThat(token.source()).isEqualTo("GH_TOKEN");
    }

    @Test
    void anEmptyEnvironmentNeverYieldsAnEnvironmentSourcedToken() {
        // Falls through to "gh auth token", which may or may not be installed on the machine running the
        // build: either a CLI-sourced token or no token at all is correct, an environment source is not.
        Token token = providerWith().token(Duration.ofMillis(500));

        if (token != null) {
            assertThat(token.source()).isEqualTo("gh auth token");
            assertThat(token.value()).isNotBlank();
        }
    }

    @Test
    void aMissingOrNonPositiveTimeoutFallsBackToTheDefaultInsteadOfFailing() {
        // A null/zero/negative timeout must not reach Process.waitFor(0) and block forever.
        assertThat(tokenSourceOrNull(providerWith("GITHUB_TOKEN", "primary").token(null)))
                .isEqualTo("GITHUB_TOKEN");
        assertThat(tokenSourceOrNull(providerWith().token(Duration.ZERO))).isNotEqualTo("GITHUB_TOKEN");
    }

    @Test
    void createReadsTheRealProcessEnvironmentWithoutThrowing() {
        assertThat(DefaultGitHubTokenProvider.create()).isNotNull();
    }

    private static String tokenSourceOrNull(Token token) {
        return token == null ? null : token.source();
    }
}
