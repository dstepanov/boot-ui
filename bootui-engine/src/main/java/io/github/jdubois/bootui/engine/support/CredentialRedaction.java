package io.github.jdubois.bootui.engine.support;

import io.github.jdubois.bootui.core.SecretMasker;
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

    private CredentialRedaction() {}

    /** Replaces any embedded user/password in {@code value} with {@link SecretMasker#MASKED_VALUE}. */
    public static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String redacted = URL_CREDENTIALS.matcher(value).replaceAll("$1" + SecretMasker.MASKED_VALUE + "@");
        return URL_CREDENTIAL_PARAMS.matcher(redacted).replaceAll("$1" + SecretMasker.MASKED_VALUE);
    }
}
