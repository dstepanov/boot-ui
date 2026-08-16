package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL/MariaDB-specific: an {@code AUTO_INCREMENT} counter approaching the maximum value its column type can
 * hold. When it arrives, every insert fails with "Duplicate entry ... for key PRIMARY" — the MySQL equivalent
 * of PostgreSQL sequence exhaustion, and just as common a cause of a sudden production outage.
 *
 * <p>The capacity is signedness-aware ({@code int} stops at 2,147,483,647 but {@code int unsigned} reaches
 * 4,294,967,295) and computed in arbitrary precision, because {@code bigint unsigned} exceeds
 * {@code Long.MAX_VALUE} and would overflow every {@code long}-based percentage. When the server does not
 * report a table's {@code AUTO_INCREMENT} value at all — which InnoDB may decline when statistics are stale
 * or disabled — the table is skipped rather than reported as empty.</p>
 */
final class MySqlAutoIncrementExhaustionRule extends AbstractDatabaseAdvisorRule {

    static final int WARNING_PERCENT_USED = 80;

    MySqlAutoIncrementExhaustionRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-MYSQL-003",
                "MySQL/MariaDB AUTO_INCREMENT nearing exhaustion",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Detects tables whose information_schema.tables.AUTO_INCREMENT has consumed at least "
                        + WARNING_PERCENT_USED + "% of the signed/unsigned capacity of its AUTO_INCREMENT column's "
                        + "integer type.",
                "Widen the AUTO_INCREMENT column (ALTER TABLE ... MODIFY ... BIGINT, or BIGINT UNSIGNED), and "
                        + "widen every foreign key column referencing it in the same migration. When the counter "
                        + "reaches the column's maximum, every subsequent insert fails with a duplicate-key error.",
                "https://dev.mysql.com/doc/refman/8.0/en/example-auto-increment.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> schemas = context.mySqlFamilySchemas();
        String skipReason = VendorRuleSupport.skipReason(
                schemas,
                VendorFindingKinds.MYSQL_AUTO_INCREMENT_COLUMNS,
                "No MySQL or MariaDB datasource was detected.");
        if (skipReason != null) {
            return skipped(skipReason);
        }
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : schemas) {
            if (!VendorRuleSupport.available(schema, VendorFindingKinds.MYSQL_AUTO_INCREMENT_COLUMNS)
                    || !VendorRuleSupport.available(schema, VendorFindingKinds.MYSQL_TABLES)) {
                continue;
            }
            for (MySqlAutoIncrementColumn column :
                    schema.vendorFindings().findings(VendorFindingKinds.MYSQL_AUTO_INCREMENT_COLUMNS)) {
                checkColumn(schema, column, details);
            }
        }
        return violation(details);
    }

    private void checkColumn(SchemaSnapshot schema, MySqlAutoIncrementColumn column, List<String> details) {
        BigInteger capacity = column.capacity();
        BigInteger nextValue = MySqlCatalogReader.nextAutoIncrement(schema, column.schema(), column.table());
        if (capacity == null || capacity.signum() <= 0 || nextValue == null || nextValue.signum() < 0) {
            // An unclassified type or an AUTO_INCREMENT the server did not report is not a clean result and
            // not a finding either: there is nothing to measure.
            return;
        }
        int percentUsed = new BigDecimal(nextValue)
                .multiply(BigDecimal.valueOf(100))
                .divide(new BigDecimal(capacity), 0, RoundingMode.DOWN)
                .min(BigDecimal.valueOf(100))
                .intValue();
        if (percentUsed < WARNING_PERCENT_USED) {
            return;
        }
        details.add(schema.dataSourceName() + ": " + column.qualifiedTable() + "." + column.column() + " ("
                + column.columnType() + ") is at " + percentUsed + "% of its AUTO_INCREMENT capacity (next value "
                + nextValue + " of " + capacity + ").");
    }
}
