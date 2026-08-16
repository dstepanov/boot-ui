package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.support.CredentialRedaction;
import io.github.jdubois.bootui.engine.support.DetailText;

/**
 * One thing the scan could not do, or could only do partially: a datasource that refused a connection, a
 * table whose metadata could not be read, a catalog augmentation blocked by permissions, or a bound that cut
 * the scan short.
 *
 * <p>Diagnostics are surfaced next to the findings instead of being folded into them, so a failure never
 * looks like a clean result and never inflates the violation count. Every message is flattened, truncated
 * and credential-redacted on construction — a JDBC error commonly echoes the connection URL.</p>
 *
 * @param source what the diagnostic is about (a datasource name, {@code datasource/table}, or a rule id)
 * @param level {@code ERROR}, {@code WARNING} or {@code INFO}
 * @param message the sanitized, redacted description
 */
record SchemaDiagnostic(String source, String level, String message) {

    static final String ERROR = "ERROR";
    static final String WARNING = "WARNING";
    static final String INFO = "INFO";

    static SchemaDiagnostic error(String source, String message) {
        return of(source, ERROR, message);
    }

    static SchemaDiagnostic warning(String source, String message) {
        return of(source, WARNING, message);
    }

    static SchemaDiagnostic info(String source, String message) {
        return of(source, INFO, message);
    }

    private static SchemaDiagnostic of(String source, String level, String message) {
        return new SchemaDiagnostic(source, level, DetailText.sanitize(CredentialRedaction.redact(message)));
    }
}
