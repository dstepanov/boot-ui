package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The typed store of every vendor catalog augmentation read for one datasource, keyed by
 * {@link VendorFindingKind}.
 *
 * <p>Lookups are type-safe through the key's declared record type, and an augmentation that was never
 * requested reads back as {@link VendorAugmentation.Status#NOT_APPLICABLE} rather than as an empty success —
 * so a rule can always distinguish "nothing found" from "nothing asked" and from "the catalog refused".</p>
 */
final class VendorFindings {

    static final VendorFindings EMPTY = new VendorFindings(Map.of());

    private final Map<String, VendorAugmentation<?>> byKind;

    private VendorFindings(Map<String, VendorAugmentation<?>> byKind) {
        this.byKind = Map.copyOf(byKind);
    }

    static Builder builder() {
        return new Builder();
    }

    @SuppressWarnings("unchecked")
    <T> VendorAugmentation<T> augmentation(VendorFindingKind<T> kind) {
        VendorAugmentation<?> stored = byKind.get(kind.id());
        if (stored == null) {
            return VendorAugmentation.notApplicable(kind, "This catalog augmentation did not run.");
        }
        return (VendorAugmentation<T>) stored;
    }

    <T> List<T> findings(VendorFindingKind<T> kind) {
        return augmentation(kind).findings();
    }

    <T> boolean available(VendorFindingKind<T> kind) {
        return augmentation(kind).available();
    }

    /** Every augmentation that failed, for scan-level diagnostics. */
    List<VendorAugmentation<?>> failures() {
        return byKind.values().stream().filter(VendorAugmentation::failed).toList();
    }

    /** Every augmentation whose bound cut the catalog result short. */
    List<VendorAugmentation<?>> truncations() {
        return byKind.values().stream()
                .filter(augmentation -> augmentation.available() && augmentation.truncated())
                .toList();
    }

    static final class Builder {

        private final Map<String, VendorAugmentation<?>> byKind = new LinkedHashMap<>();

        <T> Builder add(VendorAugmentation<T> augmentation) {
            byKind.put(augmentation.kind().id(), augmentation);
            return this;
        }

        VendorFindings build() {
            return byKind.isEmpty() ? EMPTY : new VendorFindings(byKind);
        }
    }
}
