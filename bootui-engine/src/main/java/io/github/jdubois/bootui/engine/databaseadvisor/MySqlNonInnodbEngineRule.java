package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MySQL/MariaDB-specific: an application table on a storage engine that is not crash-safe or transactional,
 * which surprises JPA/Hibernate code assuming ACID semantics — MyISAM and Aria silently ignore foreign keys
 * and roll nothing back, MEMORY loses its rows on restart, and both use table-level locking.
 *
 * <p>Deliberately narrow. Only engines whose <em>defect</em> is the lack of transactions are reported;
 * specialist engines a developer chooses on purpose (RocksDB/MyRocks, ColumnStore, NDB, FEDERATED, SPIDER,
 * CONNECT, MariaDB's SEQUENCE, ...) are not, because "you are not using InnoDB" is not a finding when the
 * engine was the point. That is also why this rule is MEDIUM rather than HIGH: it is a design review prompt,
 * not a defect, and a MyISAM full-text or archive table can be a deliberate, informed choice.</p>
 */
final class MySqlNonInnodbEngineRule extends AbstractDatabaseAdvisorRule {

    /** Engines with no transactions, no MVCC and table-level locking. */
    private static final Set<String> NON_TRANSACTIONAL_ENGINES =
            Set.of("myisam", "mrg_myisam", "merge", "memory", "heap", "csv", "archive", "blackhole", "aria");

    MySqlNonInnodbEngineRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-MYSQL-001",
                "Tables on a non-transactional storage engine",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Detects MySQL/MariaDB tables (information_schema.tables.ENGINE) on a non-transactional engine "
                        + "(MyISAM, MERGE, MEMORY, CSV, ARCHIVE, BLACKHOLE, Aria). Specialist transactional or "
                        + "purpose-built engines such as RocksDB, ColumnStore, NDB, FEDERATED, SPIDER and CONNECT "
                        + "are not reported.",
                "Convert the table to InnoDB (ALTER TABLE ... ENGINE=InnoDB) unless the engine was chosen "
                        + "deliberately. Non-transactional engines do not enforce foreign keys, do not roll back, "
                        + "and use table-level locking, which surprises most JPA/Hibernate applications that assume "
                        + "ACID semantics. Convert during a maintenance window: the rewrite locks the table and "
                        + "changes its on-disk size.",
                "https://dev.mysql.com/doc/refman/8.0/en/innodb-introduction.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> schemas = context.mySqlFamilySchemas();
        String skipReason = VendorRuleSupport.skipReason(
                schemas, VendorFindingKinds.MYSQL_TABLES, "No MySQL or MariaDB datasource was detected.");
        if (skipReason != null) {
            return skipped(skipReason);
        }
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : schemas) {
            if (!VendorRuleSupport.available(schema, VendorFindingKinds.MYSQL_TABLES)) {
                continue;
            }
            for (MySqlTableInfo table : schema.vendorFindings().findings(VendorFindingKinds.MYSQL_TABLES)) {
                if (isNonTransactional(table.engine())) {
                    details.add(schema.dataSourceName() + ": table " + table.qualifiedName() + " uses the "
                            + table.engine() + " engine, which is not transactional or crash-safe.");
                }
            }
        }
        return violation(details);
    }

    private boolean isNonTransactional(String engine) {
        return engine != null && NON_TRANSACTIONAL_ENGINES.contains(engine.toLowerCase(Locale.ROOT));
    }
}
