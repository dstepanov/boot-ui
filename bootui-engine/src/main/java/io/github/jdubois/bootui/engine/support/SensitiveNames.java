package io.github.jdubois.bootui.engine.support;

import io.github.jdubois.bootui.core.SecretMasker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Single decision point for "does this header, query-parameter, or attribute name look sensitive?".
 *
 * <p>Every telemetry surface that masks a value <em>by name</em> — inbound HTTP exchanges, outbound REST-client
 * calls, and the Quarkus capture filter — used to carry its own copy of the {@link SecretMasker} keyword check
 * plus the same hardcoded header allow-list. Keeping one copy here is what makes masking indistinguishable
 * across Spring MVC, Spring WebFlux, and Quarkus.</p>
 *
 * <p>Names are matched both as captured and percent-decoded, so a URL-encoded parameter such as
 * {@code %61pi%2Dkey} cannot evade the keyword check that plain {@code api-key} triggers.</p>
 */
public final class SensitiveNames {

    /**
     * Names treated as sensitive even though {@link SecretMasker#isSecret(String)} does not recognize them by
     * keyword.
     */
    private static final Set<String> SENSITIVE_HEADER_NAMES =
            Set.of("authorization", "proxy-authorization", "cookie", "set-cookie", "x-xsrf-token", "x-csrf-token");

    private static final SecretMasker MASKER = new SecretMasker();

    private SensitiveNames() {}

    /**
     * Returns true when {@code name} looks sensitive either as captured or once percent-decoded. A {@code null}
     * or blank name is never sensitive.
     */
    public static boolean isSensitive(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (matches(name)) {
            return true;
        }
        String decoded = decodeQueryComponent(name);
        return !decoded.equals(name) && matches(decoded);
    }

    private static boolean matches(String name) {
        return MASKER.isSecret(name) || SENSITIVE_HEADER_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Percent-decodes a query-string component, treating {@code +} as an encoded space the way an
     * {@code application/x-www-form-urlencoded} reader does. Decoding is lenient and per-escape: a malformed
     * sequence such as {@code %ZZ} is preserved literally while the valid escapes around it are still decoded, so
     * an encoded sensitive name cannot hide behind one bad escape. Never raises.
     */
    public static String decodeQueryComponent(String value) {
        return decode(value, true);
    }

    /**
     * Percent-decodes a path component. Unlike {@link #decodeQueryComponent(String)} a literal {@code +} stays a
     * {@code +}, because it carries no special meaning in a path.
     */
    public static String decodePathComponent(String value) {
        return decode(value, false);
    }

    private static String decode(String value, boolean formEncoded) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.indexOf('%') < 0 && !(formEncoded && value.indexOf('+') >= 0)) {
            return value;
        }
        StringBuilder decoded = new StringBuilder(value.length());
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            int escaped = current == '%' ? decodeEscape(value, index) : -1;
            if (escaped >= 0) {
                pending.write(escaped);
                index += 3;
                continue;
            }
            flush(pending, decoded);
            decoded.append(formEncoded && current == '+' ? ' ' : current);
            index++;
        }
        flush(pending, decoded);
        return decoded.toString();
    }

    /** The byte encoded by the {@code %XX} escape at {@code index}, or {@code -1} when it is malformed. */
    private static int decodeEscape(String value, int index) {
        if (index + 2 >= value.length()) {
            return -1;
        }
        int high = Character.digit(value.charAt(index + 1), 16);
        int low = Character.digit(value.charAt(index + 2), 16);
        return high < 0 || low < 0 ? -1 : (high << 4) + low;
    }

    /**
     * Appends the buffered escape bytes as UTF-8. Invalid byte sequences become the replacement character rather
     * than raising, because masking must never fail on hand-written or corrupted input.
     */
    private static void flush(ByteArrayOutputStream pending, StringBuilder decoded) {
        if (pending.size() == 0) {
            return;
        }
        decoded.append(pending.toString(StandardCharsets.UTF_8));
        pending.reset();
    }
}
