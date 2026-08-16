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
                        List.of(new PostgresInvalidIndex("sales", "orders", "ix_broken", false, true, true, false)),
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
                "int4",
                null);
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
                "int8",
                null);
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

    @Test
    void mySqlCharsetRuleRecommendsADialectAppropriateCollation() {
        VendorFindings mysqlFindings = VendorFindings.builder()
                .add(VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_COLUMN_CHARSETS,
                        List.of(new MySqlColumnCharset("app", "users", "bio", "utf8mb3", "utf8mb3_general_ci")),
                        false))
                .build();
        DatabaseAdvisorRuleResultDto mysqlResult = new MySqlNonUtf8mb4CharsetRule()
                .evaluate(context(schema("ds", Dialect.MYSQL, List.of(), mysqlFindings)));
        assertThat(mysqlResult.sampleViolations().get(0)).contains("utf8mb4_0900_ai_ci");

        VendorFindings mariadbFindings = VendorFindings.builder()
                .add(VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_COLUMN_CHARSETS,
                        List.of(new MySqlColumnCharset("app", "users", "bio", "utf8mb3", "utf8mb3_general_ci")),
                        false))
                .build();
        DatabaseAdvisorRuleResultDto mariadbResult = new MySqlNonUtf8mb4CharsetRule()
                .evaluate(context(schema("ds", Dialect.MARIADB, List.of(), mariadbFindings)));
        assertThat(mariadbResult.sampleViolations().get(0)).contains("utf8mb4_uca1400_ai_ci");
    }

    // --- DB-PG-001 uniqueness-impact note ---

    @Test
    void postgresInvalidIndexRuleCallsOutLostUniquenessEnforcement() {
        SchemaSnapshot postgres = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(
                        VendorFindingKinds.POSTGRES_INVALID_INDEXES,
                        List.of(new PostgresInvalidIndex("sales", "orders", "uq_broken", false, true, true, true)),
                        false));
        DatabaseAdvisorRuleResultDto result = new PostgresInvalidIndexRule().evaluate(context(postgres));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("UNIQUE").contains("not currently enforced");
    }

    @Test
    void postgresInvalidIndexRuleOmitsTheUniquenessNoteForANonUniqueIndex() {
        SchemaSnapshot postgres = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(
                        VendorFindingKinds.POSTGRES_INVALID_INDEXES,
                        List.of(new PostgresInvalidIndex("sales", "orders", "ix_broken", false, true, true, false)),
                        false));
        DatabaseAdvisorRuleResultDto result = new PostgresInvalidIndexRule().evaluate(context(postgres));
        assertThat(result.sampleViolations().get(0)).doesNotContain("UNIQUE");
    }

    // --- DB-PG-004: PostgreSQL table lacking usable replica identity ---

    @Test
    void postgresReplicaIdentityRuleFlagsAPublishedTableWithNoPrimaryKeyAndDefaultIdentity() {
        TableModel auditLog = DatabaseAdvisorFixtures.table("audit_log", List.of(), List.of(), List.of(), List.of());
        SchemaSnapshot postgres = schema(
                "ds",
                Dialect.POSTGRESQL,
                List.of(auditLog),
                VendorFindings.builder()
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES,
                                List.of(new PostgresReplicaIdentityCandidate("public", "audit_log", "d")),
                                false))
                        .build());
        DatabaseAdvisorRuleResultDto result = new PostgresReplicaIdentityRule().evaluate(context(postgres));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0))
                .contains("public.audit_log")
                .contains("no primary key");
    }

    @Test
    void postgresReplicaIdentityRuleFlagsATableExplicitlySetToNothing() {
        TableModel orders = DatabaseAdvisorFixtures.table("orders", List.of(), List.of("id"), List.of(), List.of());
        SchemaSnapshot postgres = schema(
                "ds",
                Dialect.POSTGRESQL,
                List.of(orders),
                VendorFindings.builder()
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES,
                                List.of(new PostgresReplicaIdentityCandidate("public", "orders", "n")),
                                false))
                        .build());
        DatabaseAdvisorRuleResultDto result = new PostgresReplicaIdentityRule().evaluate(context(postgres));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("NOTHING");
    }

    @Test
    void postgresReplicaIdentityRulePassesWhenTheTableHasAPrimaryKeyAndDefaultIdentity() {
        TableModel orders = DatabaseAdvisorFixtures.table("orders", List.of(), List.of("id"), List.of(), List.of());
        SchemaSnapshot postgres = schema(
                "ds",
                Dialect.POSTGRESQL,
                List.of(orders),
                VendorFindings.builder()
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES,
                                List.of(new PostgresReplicaIdentityCandidate("public", "orders", "d")),
                                false))
                        .build());
        assertThat(new PostgresReplicaIdentityRule().evaluate(context(postgres)).status())
                .isEqualTo(PASS);
    }

    @Test
    void postgresReplicaIdentityRuleDoesNotInferAMissingPrimaryKeyWhenMetadataWasUnreadable() {
        TableModel auditLog = new TableModel(
                "app",
                "public",
                "audit_log",
                "TABLE",
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                false,
                new TableMetadata(true, false, true, true, false, List.of("permission denied")));
        SchemaSnapshot postgres = schema(
                "ds",
                Dialect.POSTGRESQL,
                List.of(auditLog),
                VendorFindings.builder()
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES,
                                List.of(new PostgresReplicaIdentityCandidate("public", "audit_log", "d")),
                                false))
                        .build());

        assertThat(new PostgresReplicaIdentityRule().evaluate(context(postgres)).status())
                .isEqualTo(PASS);
    }

    @Test
    void postgresReplicaIdentityRulePassesWhenNoTableIsInScopeOfAnyPublication() {
        SchemaSnapshot postgres = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(
                        VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES, List.of(), false));
        assertThat(new PostgresReplicaIdentityRule().evaluate(context(postgres)).status())
                .isEqualTo(PASS);
    }

    @Test
    void postgresReplicaIdentityRuleSkipsWithoutAPostgresDatasource() {
        assertThat(new PostgresReplicaIdentityRule()
                        .evaluate(context(schema("ds", Dialect.MYSQL, List.of())))
                        .status())
                .isEqualTo(SKIPPED);
    }

    // --- DB-ORACLE-001: unusable Oracle indexes ---

    @Test
    void oracleUnusableIndexRuleFlagsAnOrdinaryUnusableIndex() {
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_INDEX_DETAILS,
                        List.of(new OracleIndexDetail(
                                "APP", "ORDERS", "IX_CUSTOMER", "NORMAL", false, "UNUSABLE", "VISIBLE", false, false)),
                        false));
        DatabaseAdvisorRuleResultDto result = new OracleUnusableIndexRule().evaluate(context(oracle));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("IX_CUSTOMER").contains("APP.ORDERS");
    }

    @Test
    void oracleUnusableIndexRuleExcludesDomainIndexes() {
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_INDEX_DETAILS,
                        List.of(new OracleIndexDetail(
                                "APP", "DOCS", "IX_TEXT", "DOMAIN", false, "UNUSABLE", "VISIBLE", false, false)),
                        false));
        assertThat(new OracleUnusableIndexRule().evaluate(context(oracle)).status())
                .isEqualTo(PASS);
    }

    @Test
    void oracleUnusableIndexRuleReportsAnUnusablePartitionByName() {
        SchemaSnapshot oracle = schema(
                "ds",
                Dialect.ORACLE,
                List.of(),
                VendorFindings.builder()
                        .add(VendorAugmentation.available(VendorFindingKinds.ORACLE_INDEX_DETAILS, List.of(), false))
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.ORACLE_INDEX_PARTITION_STATUS,
                                List.of(new OracleIndexPartitionStatus(
                                        "APP", "EVENTS", "IX_EVENTS", "P_2024_01", false, "UNUSABLE")),
                                false))
                        .build());
        DatabaseAdvisorRuleResultDto result = new OracleUnusableIndexRule().evaluate(context(oracle));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0))
                .contains("partition P_2024_01")
                .contains("IX_EVENTS");
    }

    @Test
    void oracleUnusableIndexRulePassesWhenEveryIndexIsValid() {
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_INDEX_DETAILS,
                        List.of(new OracleIndexDetail(
                                "APP", "ORDERS", "IX_CUSTOMER", "NORMAL", false, "VALID", "VISIBLE", false, false)),
                        false));
        assertThat(new OracleUnusableIndexRule().evaluate(context(oracle)).status())
                .isEqualTo(PASS);
    }

    // --- DB-ORACLE-002: disabled/unvalidated Oracle constraints ---

    @Test
    void oracleInvalidConstraintRuleFlagsADisabledForeignKey() {
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_CONSTRAINTS,
                        List.of(new OracleConstraintDetail(
                                "APP", "ORDERS", "FK_ORDERS_CUSTOMER", "R", "DISABLED", "NOT VALIDATED", false, null)),
                        false));
        DatabaseAdvisorRuleResultDto result = new OracleInvalidConstraintRule().evaluate(context(oracle));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0))
                .contains("FK_ORDERS_CUSTOMER")
                .contains("disabled and not validated");
    }

    @Test
    void oracleInvalidConstraintRuleFlagsAnEnabledButUnvalidatedConstraint() {
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_CONSTRAINTS,
                        List.of(new OracleConstraintDetail(
                                "APP", "ORDERS", "FK_ORDERS_CUSTOMER", "R", "ENABLED", "NOT VALIDATED", false, null)),
                        false));
        DatabaseAdvisorRuleResultDto result = new OracleInvalidConstraintRule().evaluate(context(oracle));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("enabled but not validated");
    }

    @Test
    void oracleInvalidConstraintRuleExcludesTheSystemGeneratedNotNullCheckConstraint() {
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_CONSTRAINTS,
                        List.of(new OracleConstraintDetail(
                                "APP",
                                "ORDERS",
                                "SYS_C0012345",
                                "C",
                                "DISABLED",
                                "NOT VALIDATED",
                                true,
                                "\"CUSTOMER_ID\" IS NOT NULL")),
                        false));
        assertThat(new OracleInvalidConstraintRule().evaluate(context(oracle)).status())
                .isEqualTo(PASS);
    }

    @Test
    void oracleInvalidConstraintRuleDoesNotExcludeAnUnnamedUserCheckConstraint() {
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_CONSTRAINTS,
                        List.of(new OracleConstraintDetail(
                                "APP",
                                "ORDERS",
                                "SYS_C0099999",
                                "C",
                                "DISABLED",
                                "NOT VALIDATED",
                                true,
                                "\"TOTAL\" > 0")),
                        false));
        assertThat(new OracleInvalidConstraintRule().evaluate(context(oracle)).status())
                .isEqualTo(VIOLATION);
    }

    @Test
    void oracleInvalidConstraintRulePassesWhenEnabledAndValidated() {
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_CONSTRAINTS,
                        List.of(new OracleConstraintDetail(
                                "APP", "ORDERS", "FK_ORDERS_CUSTOMER", "R", "ENABLED", "VALIDATED", false, null)),
                        false));
        assertThat(new OracleInvalidConstraintRule().evaluate(context(oracle)).status())
                .isEqualTo(PASS);
    }

    // --- DB-ORACLE-003: sequence/identity generator nearing exhaustion ---

    @Test
    void oracleSequenceExhaustionRuleFlagsANonCyclingSequenceNearItsMaxValue() {
        OracleSequenceUsage sequence = new OracleSequenceUsage(
                "APP",
                "ORDERS_SEQ",
                java.math.BigInteger.valueOf(950),
                java.math.BigInteger.valueOf(1000),
                java.math.BigInteger.ZERO,
                java.math.BigInteger.ONE,
                false,
                false);
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(VendorFindingKinds.ORACLE_SEQUENCES, List.of(sequence), false));
        DatabaseAdvisorRuleResultDto result = new OracleSequenceExhaustionRule().evaluate(context(oracle));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("ORDERS_SEQ").contains("95%");
    }

    @Test
    void oracleSequenceExhaustionRuleNamesTheIdentityColumnWhenKnown() {
        OracleSequenceUsage sequence = new OracleSequenceUsage(
                "APP",
                "ISEQ$$_74522",
                java.math.BigInteger.valueOf(950),
                java.math.BigInteger.valueOf(1000),
                java.math.BigInteger.ONE,
                java.math.BigInteger.ONE,
                false,
                false);
        OracleIdentityColumn identityColumn = new OracleIdentityColumn("APP", "ORDERS", "ID", "ISEQ$$_74522");
        SchemaSnapshot oracle = schema(
                "ds",
                Dialect.ORACLE,
                List.of(),
                VendorFindings.builder()
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.ORACLE_SEQUENCES, List.of(sequence), false))
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.ORACLE_IDENTITY_COLUMNS, List.of(identityColumn), false))
                        .build());
        DatabaseAdvisorRuleResultDto result = new OracleSequenceExhaustionRule().evaluate(context(oracle));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("APP.ORDERS.ID");
    }

    @Test
    void oracleSequenceExhaustionRuleIgnoresCyclingSequences() {
        OracleSequenceUsage sequence = new OracleSequenceUsage(
                "APP",
                "ORDERS_SEQ",
                java.math.BigInteger.valueOf(999),
                java.math.BigInteger.valueOf(1000),
                java.math.BigInteger.ONE,
                java.math.BigInteger.ONE,
                true,
                false);
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(VendorFindingKinds.ORACLE_SEQUENCES, List.of(sequence), false));
        assertThat(new OracleSequenceExhaustionRule().evaluate(context(oracle)).status())
                .isEqualTo(PASS);
    }

    @Test
    void oracleSequenceExhaustionRuleExcludesSessionScalableAndShardedSequences() {
        OracleSequenceUsage sequence = new OracleSequenceUsage(
                "APP",
                "SCALABLE_SEQ",
                java.math.BigInteger.valueOf(999),
                java.math.BigInteger.valueOf(1000),
                java.math.BigInteger.ONE,
                java.math.BigInteger.ONE,
                false,
                true);
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(VendorFindingKinds.ORACLE_SEQUENCES, List.of(sequence), false));
        assertThat(new OracleSequenceExhaustionRule().evaluate(context(oracle)).status())
                .isEqualTo(PASS);
    }
}
