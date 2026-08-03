package io.github.jdubois.bootui.core;

/**
 * Normalizes and validates the {@code bootui.path} configuration property.
 *
 * <p>Rules (fail-closed — an invalid value throws {@link IllegalArgumentException}):</p>
 * <ul>
 *   <li>Must start with {@code /}.</li>
 *   <li>Must not be {@code /} (the root path would intercept every application request).</li>
 *   <li>Must not contain {@code ..} (path-traversal prevention).</li>
 *   <li>Must not contain {@code ?} or {@code #} (no query/fragment components).</li>
 *   <li>Must not contain {@code %2F} or {@code %2f} (encoded path separators that confuse routing).</li>
 *   <li>Must not contain consecutive slashes ({@code //}) after trimming.</li>
 *   <li>Trailing slashes are silently stripped during normalization.</li>
 *   <li>Blank/null input is rejected.</li>
 * </ul>
 *
 * <p>The default {@code /bootui} path is accepted and returned unchanged. This class is pure Java with
 * no framework dependencies and may be used by any adapter.</p>
 */
public final class BootUiPathNormalizer {

    /** Default BootUI base path. */
    public static final String DEFAULT_PATH = "/bootui";

    private BootUiPathNormalizer() {
    }

    /**
     * Normalizes and validates a configured base path.
     *
     * @param path the raw {@code bootui.path} value from configuration
     * @return the normalized path (trailing slash stripped, otherwise unchanged)
     * @throws IllegalArgumentException when the path fails validation
     */
    public static String normalize(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException(
                    "bootui.path must not be blank. Use '/bootui' (the default) or another absolute path.");
        }

        String trimmed = path.strip();

        if (!trimmed.startsWith("/")) {
            throw new IllegalArgumentException(
                    "bootui.path must start with '/' but was: '" + trimmed + "'");
        }
        if (trimmed.equals("/")) {
            throw new IllegalArgumentException(
                    "bootui.path must not be '/' (the root path would intercept every application request).");
        }
        if (trimmed.contains("..")) {
            throw new IllegalArgumentException(
                    "bootui.path must not contain '..' (path traversal): '" + trimmed + "'");
        }
        if (trimmed.contains("?")) {
            throw new IllegalArgumentException(
                    "bootui.path must not contain a query component ('?'): '" + trimmed + "'");
        }
        if (trimmed.contains("#")) {
            throw new IllegalArgumentException(
                    "bootui.path must not contain a fragment component ('#'): '" + trimmed + "'");
        }
        if (trimmed.toLowerCase(java.util.Locale.ROOT).contains("%2f")) {
            throw new IllegalArgumentException(
                    "bootui.path must not contain encoded path separators ('%2F'): '" + trimmed + "'");
        }
        if (trimmed.contains("//")) {
            throw new IllegalArgumentException(
                    "bootui.path must not contain consecutive slashes ('//'): '" + trimmed + "'");
        }

        // Strip trailing slash(es)
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        return trimmed;
    }
}
