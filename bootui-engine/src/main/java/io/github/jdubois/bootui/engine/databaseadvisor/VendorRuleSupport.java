package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared skip logic for the vendor rules, so "no datasource of this dialect", "this server version has no
 * such catalog view" and "the catalog refused to answer" all reach the user as an explicit {@code SKIPPED}
 * reason instead of an unearned clean result.
 */
final class VendorRuleSupport {

    private VendorRuleSupport() {}

    /**
     * The reason this rule cannot run, or {@code null} when at least one datasource can answer it.
     *
     * @param schemas the readable schemas of the rule's dialect family
     * @param kind the catalog augmentation the rule reads
     * @param noDatasourceReason the reason to report when no datasource of that dialect exists
     */
    static String skipReason(List<SchemaSnapshot> schemas, VendorFindingKind<?> kind, String noDatasourceReason) {
        if (schemas.isEmpty()) {
            return noDatasourceReason;
        }
        List<String> reasons = new ArrayList<>();
        for (SchemaSnapshot schema : schemas) {
            VendorAugmentation<?> augmentation = schema.vendorFindings().augmentation(kind);
            if (augmentation.available()) {
                return null;
            }
            reasons.add(schema.dataSourceName() + ": " + augmentation.reason());
        }
        return String.join("; ", reasons);
    }

    static boolean available(SchemaSnapshot schema, VendorFindingKind<?> kind) {
        return schema.vendorFindings().available(kind);
    }
}
