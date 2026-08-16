package io.github.jdubois.bootui.engine.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CredentialRedactionTests {

    @Test
    void redactsUserAndPasswordEmbeddedInAConnectionUrl() {
        String redacted = CredentialRedaction.redact("jdbc:postgresql://app:sup3rs3cret@db.internal:5432/orders");
        assertThat(redacted).isEqualTo("jdbc:postgresql://******@db.internal:5432/orders");
    }

    @Test
    void redactsCredentialQueryParametersInBothCommonSeparatorStyles() {
        assertThat(CredentialRedaction.redact("jdbc:mysql://db/app?user=root&password=hunter2&useSsl=true"))
                .isEqualTo("jdbc:mysql://db/app?user=******&password=******&useSsl=true");
        assertThat(CredentialRedaction.redact("jdbc:sqlserver://db;user=sa;password=hunter2;encrypt=true"))
                .isEqualTo("jdbc:sqlserver://db;user=******;password=******;encrypt=true");
    }

    @Test
    void redactsCredentialsInsideADriverErrorMessage() {
        String message = "FATAL: password authentication failed while opening "
                + "jdbc:postgresql://reporting:s3cr3t@10.0.0.4:5432/warehouse";
        assertThat(CredentialRedaction.redact(message)).doesNotContain("s3cr3t").contains("******@10.0.0.4");
    }

    @Test
    void leavesTextWithoutCredentialsUnchanged() {
        String message = "relation \"orders\" does not exist";
        assertThat(CredentialRedaction.redact(message)).isEqualTo(message);
        assertThat(CredentialRedaction.redact(null)).isNull();
        assertThat(CredentialRedaction.redact("")).isEmpty();
    }
}
