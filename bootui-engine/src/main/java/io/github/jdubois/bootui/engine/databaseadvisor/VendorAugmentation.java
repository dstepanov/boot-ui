package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.List;

/**
 * The outcome of one vendor catalog augmentation: its findings plus whether the catalog could answer at all.
 *
 * <p>{@link Status#FAILED} (a permission error, a missing catalog view, a statement timeout) is deliberately
 * distinct from {@link Status#AVAILABLE} with zero findings, so a rule reports {@code SKIPPED} with the real
 * reason instead of a clean {@code PASS} it did not earn.</p>
 *
 * @param kind the augmentation this result belongs to
 * @param status whether the catalog data is usable
 * @param reason why the data is unavailable, already sanitized/redacted; {@code null} when available
 * @param findings the rows read, always bounded
 * @param truncated whether the bound cut the result short (more rows exist)
 */
record VendorAugmentation<T>(
        VendorFindingKind<T> kind, Status status, String reason, List<T> findings, boolean truncated) {

    enum Status {
        /** The catalog answered; {@link #findings()} is complete up to {@link #truncated()}. */
        AVAILABLE,
        /** The augmentation does not apply here (wrong dialect, or unsupported server version). */
        NOT_APPLICABLE,
        /** The catalog could not be read (permissions, missing view, timeout). */
        FAILED
    }

    VendorAugmentation {
        findings = List.copyOf(findings);
    }

    static <T> VendorAugmentation<T> available(VendorFindingKind<T> kind, List<T> findings, boolean truncated) {
        return new VendorAugmentation<>(kind, Status.AVAILABLE, null, findings, truncated);
    }

    static <T> VendorAugmentation<T> notApplicable(VendorFindingKind<T> kind, String reason) {
        return new VendorAugmentation<>(kind, Status.NOT_APPLICABLE, reason, List.of(), false);
    }

    static <T> VendorAugmentation<T> failed(VendorFindingKind<T> kind, String reason) {
        return new VendorAugmentation<>(kind, Status.FAILED, reason, List.of(), false);
    }

    boolean available() {
        return status == Status.AVAILABLE;
    }

    boolean failed() {
        return status == Status.FAILED;
    }
}
