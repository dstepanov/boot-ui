package io.github.jdubois.bootui.engine.vulnerabilities;

import java.time.Duration;

/**
 * The immutable settings {@link OsvVulnerabilityScanner} runs under, mapped once by each adapter from the same
 * {@code bootui.vulnerabilities.*} keys with the same defaults.
 *
 * <p>This follows the engine's <em>static settings record</em> template rather than a live-policy SPI: the
 * scan has no UI override or re-bind path, so the values are resolved at startup. The two that matter most are
 * the bounds — {@code maxPackages}, {@code maxAdvisories} and {@code requestTimeout} keep a user-triggered
 * scan finite — and the base URIs, which default to the public OSV.dev and FIRST.org endpoints and are
 * overridable so tests can point the scanner at a loopback stub.</p>
 *
 * <p>{@code bootui.vulnerabilities.osv-enabled} is deliberately absent: the panel's enable/read-only gate is
 * evaluated by each adapter's controller before it ever calls the scanner, so carrying the flag here would be
 * a second, unread copy of that decision.</p>
 */
public record OsvScannerSettings(
        Duration requestTimeout,
        int maxPackages,
        int maxAdvisories,
        String baseUri,
        boolean epssEnabled,
        String epssBaseUri) {

    /** Public OSV.dev API endpoint. */
    public static final String DEFAULT_BASE_URI = "https://api.osv.dev";

    /** Public FIRST.org EPSS API endpoint. */
    public static final String DEFAULT_EPSS_BASE_URI = "https://api.first.org";
}
