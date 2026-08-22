package io.github.jdubois.bootui.engine.support;

import io.github.jdubois.bootui.core.SecretMasker;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared, unconditional redaction of credentials embedded in connection strings and the error messages that
 * echo them.
 *
 * <p>These are the two URL-credential patterns the Database Connection Pools panel has always applied to a
 * JDBC URL ({@code scheme://user:password@host} and {@code ?user=/password=} query parameters), lifted into
 * one reusable engine helper so any surface that can leak a connection string — most importantly a raw
 * {@code SQLException} message from the Database Advisor, which frequently quotes the full JDBC URL — is
 * redacted the same way, with no dependency on an adapter.</p>
 *
 * <p>Unlike the panel's exposure-policy-driven masking, this is not configurable: a driver error message is
 * never a property value the user asked to see, so the credential is always removed.</p>
 */
public final class CredentialRedaction {

    private static final Pattern URL_CREDENTIALS =
            Pattern.compile("([a-z][a-z0-9+.-]*://)([^:/@\\s]+):([^@\\s]+)@", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_CREDENTIAL_PARAMS =
            Pattern.compile("([?&;](?:user|username|password|passwd|pwd)=)([^&;\\s]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_USER_INFO = Pattern.compile("(//)[^/?#\\s]*@");
    private static final Pattern QUERY_PARAM = Pattern.compile("[?&;#]([^?&;#=\\s]+)=([^&;#\\s]*)");

    /**
     * How many layers of nested (percent-encoded) URL a value is inspected through before giving up. Three is far
     * past anything a real redirect chain carries, and bounds the work done per captured error message.
     */
    private static final int MAX_NESTED_URL_DEPTH = 3;

    private CredentialRedaction() {}

    /** Replaces any embedded user/password in {@code value} with {@link SecretMasker#MASKED_VALUE}. */
    public static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String redacted = URL_CREDENTIALS.matcher(value).replaceAll("$1" + SecretMasker.MASKED_VALUE + "@");
        return URL_CREDENTIAL_PARAMS.matcher(redacted).replaceAll("$1" + SecretMasker.MASKED_VALUE);
    }

    /**
     * {@link #redact(String)} widened for free-form text that may quote a whole request URL — most importantly an
     * HTTP client exception message. On top of the connection-string patterns it removes <em>any</em> authority
     * user-info (including a percent-encoded {@code user%3Apass@host} or a scheme-relative {@code //user:pass@host}
     * that {@link #redact(String)} does not recognize) and masks the value of every embedded query, matrix, or
     * fragment parameter that either has a sensitive name ({@link SensitiveNames}) or itself
     * {@linkplain #carriesCredentials(String) carries a nested credential-bearing URL}. An echoed
     * {@code ?access_token=...} therefore leaks no more than the same parameter would in the panel's URI column.
     *
     * <p>Like {@link #redact(String)} this is unconditional: an exception message is not a value the developer
     * asked to see, so it is never widened by {@code bootui.expose-values}.</p>
     */
    public static String redactMessage(String value) {
        return redactMessage(value, MAX_NESTED_URL_DEPTH);
    }

    /**
     * True when a value embeds a URL that itself carries credentials — a nested {@code user:secret@host} or a
     * nested sensitive parameter — as a percent-encoded {@code redirect=} target commonly does. Inspected both as
     * captured and percent-decoded, through at most {@link #MAX_NESTED_URL_DEPTH} layers of encoding.
     */
    public static boolean carriesCredentials(String value) {
        return carriesCredentials(value, MAX_NESTED_URL_DEPTH);
    }

    private static String redactMessage(String value, int depth) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String redacted = URL_USER_INFO
                .matcher(redact(value))
                .replaceAll("$1" + Matcher.quoteReplacement(SecretMasker.MASKED_VALUE) + "@");
        Matcher matcher = QUERY_PARAM.matcher(redacted);
        StringBuilder result = new StringBuilder(redacted.length());
        int copiedTo = 0;
        while (matcher.find()) {
            if (!SensitiveNames.isSensitive(matcher.group(1)) && !carriesCredentials(matcher.group(2), depth - 1)) {
                continue;
            }
            int valueStart = matcher.start(2);
            result.append(redacted, copiedTo, valueStart).append(SecretMasker.MASKED_VALUE);
            copiedTo = matcher.end(2);
        }
        return result.append(redacted, copiedTo, redacted.length()).toString();
    }

    private static boolean carriesCredentials(String value, int depth) {
        if (value == null || value.isEmpty() || depth <= 0) {
            return false;
        }
        if (!redactMessage(value, depth - 1).equals(value)) {
            return true;
        }
        String decoded = SensitiveNames.decodeQueryComponent(value);
        return !decoded.equals(value) && !redactMessage(decoded, depth - 1).equals(decoded);
    }
}
