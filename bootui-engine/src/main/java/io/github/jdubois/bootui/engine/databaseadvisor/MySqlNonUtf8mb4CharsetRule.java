package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MySQL/MariaDB-specific: a table default or a column still using the legacy three-byte {@code utf8}
 * (a.k.a. {@code utf8mb3}) encoding, which cannot store the full Unicode range — emoji and many CJK
 * supplementary characters are truncated or rejected outright.
 *
 * <p>Only {@code utf8}/{@code utf8mb3} is reported. Every other non-{@code utf8mb4} character set the
 * previous version flagged ({@code latin1}, {@code ascii}, {@code binary}, {@code ucs2}, ...) is almost
 * always a deliberate choice for a specific column, and reporting each one turned this rule into a wall of
 * noise on any legacy schema. {@code utf8mb3} is different: it is the trap MySQL created by naming a
 * three-byte encoding "utf8", so a developer who asked for Unicode did not get it.</p>
 *
 * <p>The suggested {@code utf8mb4} collation is dialect-specific and appended per finding rather than baked
 * into the shared recommendation text: MySQL 8.0's default, {@code utf8mb4_0900_ai_ci}, does not exist on
 * MariaDB at all, which never shipped the Unicode 9.0 collations it is built on.</p>
 */
final class MySqlNonUtf8mb4CharsetRule extends AbstractDatabaseAdvisorRule {

    private static final Set<String> LEGACY_UTF8_CHARSETS = Set.of("utf8", "utf8mb3");

    MySqlNonUtf8mb4CharsetRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-MYSQL-002",
                "Tables/columns using the legacy utf8mb3 character set",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Detects MySQL/MariaDB table defaults (information_schema.tables.TABLE_COLLATION) and columns "
                        + "(information_schema.columns.CHARACTER_SET_NAME) using utf8/utf8mb3. Other legacy "
                        + "character sets such as latin1 or ascii are treated as deliberate and are not reported.",
                "Convert the column and the table default to utf8mb4 (ALTER TABLE ... CONVERT TO CHARACTER SET "
                        + "utf8mb4 COLLATE <dialect-appropriate collation, named in each finding>). MySQL's legacy "
                        + "utf8 alias is a three-byte encoding that cannot store the full Unicode range, which "
                        + "surfaces as silent truncation or an insert failure. Convert during a maintenance window "
                        + "and re-check index key lengths first: utf8mb4 needs 4 bytes per character, so an "
                        + "existing index on a long VARCHAR can exceed the maximum key length.",
                "https://dev.mysql.com/doc/refman/8.0/en/charset-unicode-utf8mb4.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> schemas = context.mySqlFamilySchemas();
        String skipReason = VendorRuleSupport.skipReason(
                schemas, VendorFindingKinds.MYSQL_COLUMN_CHARSETS, "No MySQL or MariaDB datasource was detected.");
        if (skipReason != null) {
            return skipped(skipReason);
        }
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : schemas) {
            collectTableDefaults(schema, details);
            collectColumns(schema, details);
        }
        return violation(details);
    }

    private void collectTableDefaults(SchemaSnapshot schema, List<String> details) {
        if (!VendorRuleSupport.available(schema, VendorFindingKinds.MYSQL_TABLES)) {
            return;
        }
        for (MySqlTableInfo table : schema.vendorFindings().findings(VendorFindingKinds.MYSQL_TABLES)) {
            if (isLegacyUtf8(table.characterSet())) {
                details.add(schema.dataSourceName() + ": table " + table.qualifiedName()
                        + " defaults to character set " + table.characterSet() + " (collation "
                        + table.collation() + ") instead of utf8mb4. " + recommendedCollation(schema.dialect()));
            }
        }
    }

    private void collectColumns(SchemaSnapshot schema, List<String> details) {
        if (!VendorRuleSupport.available(schema, VendorFindingKinds.MYSQL_COLUMN_CHARSETS)) {
            return;
        }
        for (MySqlColumnCharset column : schema.vendorFindings().findings(VendorFindingKinds.MYSQL_COLUMN_CHARSETS)) {
            if (isLegacyUtf8(column.characterSet())) {
                details.add(schema.dataSourceName() + ": column " + column.qualifiedColumn()
                        + " uses character set " + column.characterSet()
                        + ", a three-byte encoding that cannot store the full Unicode range. "
                        + recommendedCollation(schema.dialect()));
            }
        }
    }

    private boolean isLegacyUtf8(String characterSet) {
        return characterSet != null && LEGACY_UTF8_CHARSETS.contains(characterSet.toLowerCase(Locale.ROOT));
    }

    /**
     * MySQL 8.0's {@code utf8mb4_0900_ai_ci} default does not exist on MariaDB, which never adopted MySQL's
     * Unicode 9.0 collations; recommending it there would be advice the developer cannot even apply.
     */
    private String recommendedCollation(Dialect dialect) {
        if (dialect == Dialect.MARIADB) {
            return "On MariaDB, prefer utf8mb4_uca1400_ai_ci (10.10+) or utf8mb4_general_ci (older MariaDB) — "
                    + "MySQL's utf8mb4_0900_ai_ci collation does not exist on MariaDB.";
        }
        return "On MySQL 8.0+, utf8mb4_0900_ai_ci is the server default; use utf8mb4_general_ci for older MySQL.";
    }
}
