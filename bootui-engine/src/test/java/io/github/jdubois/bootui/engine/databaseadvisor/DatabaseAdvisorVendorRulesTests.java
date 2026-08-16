package io.github.jdubois.bootui.engine.databaseadvisor;

import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.context;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.schema;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.vendorSchema;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

/** PostgreSQL and MySQL/MariaDB catalog-augmentation rules (DB-PG-*, DB-MYSQL-*). */
class DatabaseAdvisorVendorRulesTests {

    private static final String PASS = DatabaseAdvisorRuleSupport.PASS;
    private static final String VIOLATION = DatabaseAdvisorRuleSupport.VIOLATION;
    private static final String SKIPPED = DatabaseAdvisorRuleSupport.SKIPPED;

    // --- DB-PG-001: invalid PostgreSQL indexes ---

    @Test
    void postgresInvalidIndexRuleSkipsWhenNoPostgresDatasourceIsPresent() {
        DatabaseAdvisorRuleResultDto result =
                new PostgresInvalidIndexRule().evaluate(context(schema("ds", Dialect.GENERIC, List.of())));
        assertThat(result.status()).isEqualTo(SKIPPED);
        assertThat(result.sampleViolations().get(0)).contains("No PostgreSQL datasource");
    }

    @Test
    void postgresInvalidIndexRuleSkipsWithTheCatalogReasonWhenPgIndexCannotBeRead() {
        SchemaSnapshot blocked = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.failed(
                        VendorFindingKinds.POSTGRES_INVALID_INDEXES,
                        "PostgreSQL invalid indexes could not be read: permission denied for table pg_index"));
        DatabaseAdvisorRuleResultDto result = new PostgresInvalidIndexRule().evaluate(context(blocked));
        assertThat(result.status()).isEqualTo(SKIPPED);
        assertThat(result.sampleViolations().get(0)).contains("permission denied");
    }

    @Test
    void postgresInvalidIndexRuleReportsSchemaQualifiedFindingsWithTheirFlags() {
        SchemaSnapshot postgres = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(
                        VendorFindingKinds.POSTGRES_INVALID_INDEXES,
                        List.of(new PostgresInvalidIndex("sales", "orders", "ix_broken", false, true, true)),
                        false));
        DatabaseAdvisorRuleResultDto result = new PostgresInvalidIndexRule().evaluate(context(postgres));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0))
                .contains("ix_broken")
                .contains("sales.orders")
                .contains("indisvalid=false");
    }

    @Test
    void postgresInvalidIndexRulePassesWhenTheCatalogReportsNoInvalidIndexes() {
        SchemaSnapshot postgres = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(VendorFindingKinds.POSTGRES_INVALID_INDEXES, List.of(), false));
        assertThat(new PostgresInvalidIndexRule().evaluate(context(postgres)).status())
                .isEqualTo(PASS);
    }

    // --- DB-PG-002: sequence nearing exhaustion ---

    @Test
    void postgresSequenceRuleMeasuresAgainstTheOwningColumnCapacityNotTheSequenceMaximum() {
        // A bigint sequence feeding an integer column: 0% of the sequence's own range, 93% of the column's.
        PostgresSequenceUsage usage = new PostgresSequenceUsage(
                "public",
                "orders_id_seq",
                BigInteger.valueOf(2_000_000_000L),
                BigInteger.valueOf(Long.MAX_VALUE),
                BigInteger.valueOf(Integer.MAX_VALUE),
                false,
                "public",
                "orders",
                "id",
                "int4");
        SchemaSnapshot postgres = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(VendorFindingKinds.POSTGRES_SEQUENCES, List.of(usage), false));
        DatabaseAdvisorRuleResultDto result = new PostgresSequenceExhaustionRule().evaluate(context(postgres));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0))
                .contains("orders_id_seq")
                .contains("public.orders.id")
                .contains("limited by its owning column type");
    }

    @Test
    void postgresSequenceRuleIgnoresCyclingSequences() {
        PostgresSequenceUsage usage = new PostgresSequenceUsage(
                "public",
                "tickets_seq",
                BigInteger.valueOf(999),
                BigInteger.valueOf(1000),
                null,
                true,
                null,
                null,
                null,
                null);
        SchemaSnapshot postgres = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(VendorFindingKinds.POSTGRES_SEQUENCES, List.of(usage), false));
        assertThat(new PostgresSequenceExhaustionRule()
                        .evaluate(context(postgres))
                        .status())
                .isEqualTo(PASS);
    }

    @Test
    void postgresSequenceRuleComputesPercentagesWithoutOverflowingOnBigintRanges() {
        PostgresSequenceUsage usage = new PostgresSequenceUsage(
                "public",
                "events_seq",
                BigInteger.valueOf(Long.MAX_VALUE).subtract(BigInteger.ONE),
                BigInteger.valueOf(Long.MAX_VALUE),
                BigInteger.valueOf(Long.MAX_VALUE),
                false,
                "public",
                "events",
                "id",
                "int8");
        // A long-based (lastValue * 100) computation would overflow here and produce a negative percentage.
        assertThat(usage.percentUsed()).isEqualTo(99);
        SchemaSnapshot postgres = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(VendorFindingKinds.POSTGRES_SEQUENCES, List.of(usage), false));
        assertThat(new PostgresSequenceExhaustionRule()
                        .evaluate(context(postgres))
                        .status())
                .isEqualTo(VIOLATION);
    }

    @Test
    void postgresSequenceRuleSkipsWhenTheSequenceViewIsUnsupported() {
        SchemaSnapshot old = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.notApplicable(
                        VendorFindingKinds.POSTGRES_SEQUENCES, "The pg_sequences view requires PostgreSQL 10"));
        DatabaseAdvisorRuleResultDto result = new PostgresSequenceExhaustionRule().evaluate(context(old));
        assertThat(result.status()).isEqualTo(SKIPPED);
        assertThat(result.sampleViolations().get(0)).contains("pg_sequences");
    }

    // --- DB-PG-003: NOT VALID constraints ---

    @Test
    void postgresUnvalidatedConstraintRuleReportsForeignKeyAndCheckConstraints() {
        SchemaSnapshot postgres = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(
                        VendorFindingKinds.POSTGRES_UNVALIDATED_CONSTRAINTS,
                        List.of(
                                new PostgresUnvalidatedConstraint(
                                        "public", "orders", "fk_orders_customer", "f", "FOREIGN KEY ..."),
                                new PostgresUnvalidatedConstraint(
                                        "public", "orders", "ck_total_positive", "c", "CHECK ...")),
                        false));
        DatabaseAdvisorRuleResultDto result = new PostgresUnvalidatedConstraintRule().evaluate(context(postgres));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations().get(0)).contains("foreign key constraint fk_orders_customer");
        assertThat(result.sampleViolations().get(1)).contains("check constraint ck_total_positive");
    }

    @Test
    void postgresUnvalidatedConstraintRuleSkipsWithoutAPostgresDatasource() {
        assertThat(new PostgresUnvalidatedConstraintRule()
                        .evaluate(context(schema("ds", Dialect.MYSQL, List.of())))
                        .status())
                .isEqualTo(SKIPPED);
    }

    // --- DB-MYSQL-001: non-transactional storage engines ---

    @Test
    void mySqlEngineRuleFlagsNonTransactionalEnginesOnMySqlAndMariaDb() {
        SchemaSnapshot mysql = vendorSchema(
                "mysql-ds",
                Dialect.MYSQL,
                VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_TABLES,
                        List.of(new MySqlTableInfo("app", "legacy_sessions", "MyISAM", "utf8mb4_0900_ai_ci", null)),
                        false));
        SchemaSnapshot mariadb = vendorSchema(
                "mariadb-ds",
                Dialect.MARIADB,
                VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_TABLES,
                        List.of(new MySqlTableInfo("app", "cache_entries", "Aria", "utf8mb4_general_ci", null)),
                        false));
        DatabaseAdvisorRuleResultDto result = new MySqlNonInnodbEngineRule().evaluate(context(List.of(mysql, mariadb)));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations().get(0)).contains("legacy_sessions").contains("MyISAM");
        assertThat(result.sampleViolations().get(1)).contains("cache_entries").contains("Aria");
    }

    @Test
    void mySqlEngineRuleLeavesDeliberateSpecialistEnginesAlone() {
        SchemaSnapshot mysql = vendorSchema(
                "ds",
                Dialect.MYSQL,
                VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_TABLES,
                        List.of(
                                new MySqlTableInfo("app", "orders", "InnoDB", "utf8mb4_0900_ai_ci", null),
                                new MySqlTableInfo("app", "metrics", "ROCKSDB", "utf8mb4_0900_ai_ci", null),
                                new MySqlTableInfo("app", "remote_view", "FEDERATED", "utf8mb4_0900_ai_ci", null)),
                        false));
        assertThat(new MySqlNonInnodbEngineRule().evaluate(context(mysql)).status())
                .isEqualTo(PASS);
    }

    @Test
    void mySqlEngineRuleIsMediumSeverity() {
        assertThat(new MySqlNonInnodbEngineRule().definition().severity()).isEqualTo(DatabaseAdvisorRuleSupport.MEDIUM);
    }

    // --- DB-MYSQL-002: legacy utf8mb3 ---

    @Test
    void mySqlCharsetRuleFlagsUtf8mb3TableDefaultsAndColumnsOnly() {
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_TABLES,
                        List.of(
                                new MySqlTableInfo("app", "legacy", "InnoDB", "utf8mb3_general_ci", null),
                                new MySqlTableInfo("app", "ascii_codes", "InnoDB", "latin1_swedish_ci", null)),
                        false))
                .add(VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_COLUMN_CHARSETS,
                        List.of(
                                new MySqlColumnCharset("app", "users", "bio", "utf8", "utf8_general_ci"),
                                new MySqlColumnCharset("app", "users", "country", "latin1", "latin1_swedish_ci")),
                        false))
                .build();
        SchemaSnapshot mysql = schema("ds", Dialect.MYSQL, List.of(), findings);
        DatabaseAdvisorRuleResultDto result = new MySqlNonUtf8mb4CharsetRule().evaluate(context(mysql));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("app.legacy"));
        assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("app.users.bio"));
        assertThat(result.sampleViolations()).noneMatch(detail -> detail.contains("latin1"));
    }

    // --- DB-MYSQL-003: AUTO_INCREMENT exhaustion ---

    @Test
    void mySqlAutoIncrementRuleIsSignednessAware() {
        BigInteger nearSignedIntLimit = BigInteger.valueOf(2_000_000_000L);
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_TABLES,
                        List.of(
                                new MySqlTableInfo("app", "orders", "InnoDB", "utf8mb4_0900_ai_ci", nearSignedIntLimit),
                                new MySqlTableInfo(
                                        "app", "events", "InnoDB", "utf8mb4_0900_ai_ci", nearSignedIntLimit)),
                        false))
                .add(VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_AUTO_INCREMENT_COLUMNS,
                        List.of(
                                new MySqlAutoIncrementColumn("app", "orders", "id", "int", "int(11)"),
                                new MySqlAutoIncrementColumn("app", "events", "id", "int", "int(10) unsigned")),
                        false))
                .build();
        DatabaseAdvisorRuleResultDto result = new MySqlAutoIncrementExhaustionRule()
                .evaluate(context(schema("ds", Dialect.MYSQL, List.of(), findings)));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations().get(0)).contains("app.orders.id").contains("int(11)");
    }

    @Test
    void mySqlAutoIncrementRuleHandlesBigintUnsignedWithoutOverflow() {
        BigInteger huge = new BigInteger("18000000000000000000");
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_TABLES,
                        List.of(new MySqlTableInfo("app", "events", "InnoDB", "utf8mb4_0900_ai_ci", huge)),
                        false))
                .add(VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_AUTO_INCREMENT_COLUMNS,
                        List.of(new MySqlAutoIncrementColumn("app", "events", "id", "bigint", "bigint unsigned")),
                        false))
                .build();
        DatabaseAdvisorRuleResultDto result = new MySqlAutoIncrementExhaustionRule()
                .evaluate(context(schema("ds", Dialect.MARIADB, List.of(), findings)));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("97%");
    }

    @Test
    void mySqlAutoIncrementRuleSkipsTablesWhoseCounterTheServerDidNotReport() {
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_TABLES,
                        List.of(new MySqlTableInfo("app", "orders", "InnoDB", "utf8mb4_0900_ai_ci", null)),
                        false))
                .add(VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_AUTO_INCREMENT_COLUMNS,
                        List.of(new MySqlAutoIncrementColumn("app", "orders", "id", "int", "int(11)")),
                        false))
                .build();
        assertThat(new MySqlAutoIncrementExhaustionRule()
                        .evaluate(context(schema("ds", Dialect.MYSQL, List.of(), findings)))
                        .status())
                .isEqualTo(PASS);
    }
}
