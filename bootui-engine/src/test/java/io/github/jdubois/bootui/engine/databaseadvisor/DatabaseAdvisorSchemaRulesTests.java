package io.github.jdubois.bootui.engine.databaseadvisor;

import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.column;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.context;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.expressionIndex;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.foreignKey;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.index;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.invalidIndex;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.invisibleIndex;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.partialIndex;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.prefixIndex;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.schema;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.table;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.uniqueIndex;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Schema-only rules (DB-SCHEMA-001..005), which run against every JDBC-reachable datasource. */
class DatabaseAdvisorSchemaRulesTests {

    private static final String PASS = DatabaseAdvisorRuleSupport.PASS;
    private static final String VIOLATION = DatabaseAdvisorRuleSupport.VIOLATION;

    // --- DB-SCHEMA-001: missing primary key ---

    @Test
    void missingPrimaryKeyRulePassesWhenEveryTableHasAPrimaryKey() {
        TableModel accounts =
                table("accounts", List.of(column("id", "int4", Types.INTEGER)), List.of("id"), List.of(), List.of());
        DatabaseAdvisorRuleResultDto result =
                new MissingPrimaryKeyRule().evaluate(context(schema("ds", Dialect.GENERIC, List.of(accounts))));
        assertThat(result.status()).isEqualTo(PASS);
    }

    @Test
    void missingPrimaryKeyRuleFlagsTablesWithNoPrimaryKeyColumns() {
        TableModel auditLog = table(
                "audit_log",
                List.of(column("message", "varchar", Types.VARCHAR, 255)),
                List.of(),
                List.of(),
                List.of());
        DatabaseAdvisorRuleResultDto result =
                new MissingPrimaryKeyRule().evaluate(context(schema("ds", Dialect.GENERIC, List.of(auditLog))));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations().get(0))
                .contains("public.audit_log")
                .contains("no primary key");
    }

    @Test
    void missingPrimaryKeyRuleSkipsMigrationBookkeepingTables() {
        TableModel changeLog = table("DATABASECHANGELOG", List.of(), List.of(), List.of(), List.of());
        TableModel flyway = table("flyway_schema_history", List.of(), List.of(), List.of(), List.of());
        DatabaseAdvisorRuleResultDto result = new MissingPrimaryKeyRule()
                .evaluate(context(schema("ds", Dialect.GENERIC, List.of(changeLog, flyway))));
        assertThat(result.status()).isEqualTo(PASS);
    }

    @Test
    void missingPrimaryKeyRuleSkipsExtensionOwnedTablesAndChildPartitions() {
        TableModel extensionTable = table("spatial_ref_sys", List.of(), List.of(), List.of(), List.of())
                .withPlacement(false, false, true);
        TableModel childPartition = table("events_2024_01", List.of(), List.of(), List.of(), List.of())
                .withPlacement(false, true, false);
        DatabaseAdvisorRuleResultDto result = new MissingPrimaryKeyRule()
                .evaluate(context(schema("ds", Dialect.POSTGRESQL, List.of(extensionTable, childPartition))));
        assertThat(result.status()).isEqualTo(PASS);
    }

    @Test
    void missingPrimaryKeyRuleSkipsTablesWhoseKeyMetadataCouldNotBeRead() {
        TableModel unreadable = new TableModel(
                "app",
                "public",
                "orders",
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
        DatabaseAdvisorRuleResultDto result =
                new MissingPrimaryKeyRule().evaluate(context(schema("ds", Dialect.GENERIC, List.of(unreadable))));
        assertThat(result.status()).isEqualTo(PASS);
    }

    // --- DB-SCHEMA-002: foreign key without a usable supporting index ---

    @Test
    void missingForeignKeyIndexRulePassesWhenForeignKeyHasALeadingIndex() {
        TableModel orders = table(
                "orders",
                List.of(column("customer_id", "int8", Types.BIGINT)),
                List.of(),
                List.of(foreignKey("fk_orders_customer", List.of("customer_id"), "customers", List.of("id"))),
                List.of(index("ix_orders_customer", List.of("customer_id"))));
        DatabaseAdvisorRuleResultDto result =
                new MissingForeignKeyIndexRule().evaluate(context(schema("ds", Dialect.GENERIC, List.of(orders))));
        assertThat(result.status()).isEqualTo(PASS);
    }

    @Test
    void missingForeignKeyIndexRuleRequiresTheCompleteOrderedCompositeKeyAsLeadingPrefix() {
        TableModel orderLines = table(
                "order_lines",
                List.of(column("tenant_id", "int8", Types.BIGINT), column("order_id", "int8", Types.BIGINT)),
                List.of(),
                List.of(foreignKey(
                        "fk_lines_order", List.of("tenant_id", "order_id"), "orders", List.of("tenant_id", "id"))),
                List.of(index("ix_lines_tenant", List.of("tenant_id"))));
        DatabaseAdvisorRuleResultDto result =
                new MissingForeignKeyIndexRule().evaluate(context(schema("ds", Dialect.GENERIC, List.of(orderLines))));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("fk_lines_order").contains("tenant_id, order_id");
    }

    @Test
    void missingForeignKeyIndexRuleAcceptsAnIndexThatLeadsWithTheWholeCompositeKey() {
        TableModel orderLines = table(
                "order_lines",
                List.of(column("tenant_id", "int8", Types.BIGINT), column("order_id", "int8", Types.BIGINT)),
                List.of(),
                List.of(foreignKey(
                        "fk_lines_order", List.of("tenant_id", "order_id"), "orders", List.of("tenant_id", "id"))),
                List.of(index("ix_lines", List.of("tenant_id", "order_id", "line_no"))));
        DatabaseAdvisorRuleResultDto result =
                new MissingForeignKeyIndexRule().evaluate(context(schema("ds", Dialect.GENERIC, List.of(orderLines))));
        assertThat(result.status()).isEqualTo(PASS);
    }

    @Test
    void missingForeignKeyIndexRuleRejectsUnusableIndexes() {
        List<IndexModel> unusable = List.of(
                prefixIndex("ix_prefix", "customer_id", 10, false),
                partialIndex("ix_partial", List.of("customer_id"), "deleted_at is null"),
                invisibleIndex("ix_invisible", List.of("customer_id")),
                invalidIndex("ix_invalid", List.of("customer_id")),
                expressionIndex("ix_expression", "lower(customer_id)"));
        TableModel orders = table(
                "orders",
                List.of(column("customer_id", "int8", Types.BIGINT)),
                List.of(),
                List.of(foreignKey("fk_orders_customer", List.of("customer_id"), "customers", List.of("id"))),
                unusable);
        DatabaseAdvisorRuleResultDto result =
                new MissingForeignKeyIndexRule().evaluate(context(schema("ds", Dialect.GENERIC, List.of(orders))));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.violationCount()).isEqualTo(1);
    }

    // --- DB-SCHEMA-003: duplicate/redundant indexes ---

    @Test
    void duplicateIndexRulePassesWhenIndexesDoNotOverlap() {
        TableModel products = table(
                "products",
                List.of(),
                List.of(),
                List.of(),
                List.of(index("ix_sku", List.of("sku")), index("ix_name", List.of("name"))));
        DatabaseAdvisorRuleResultDto result =
                new DuplicateIndexRule().evaluate(context(schema("ds", Dialect.GENERIC, List.of(products))));
        assertThat(result.status()).isEqualTo(PASS);
    }

    @Test
    void duplicateIndexRuleFlagsAPrefixOverlapWithIdenticalSemantics() {
        TableModel products = table(
                "products",
                List.of(),
                List.of(),
                List.of(),
                List.of(index("ix_sku", List.of("sku")), index("ix_sku_name", List.of("sku", "name"))));
        DatabaseAdvisorRuleResultDto result =
                new DuplicateIndexRule().evaluate(context(schema("ds", Dialect.GENERIC, List.of(products))));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations().get(0)).contains("ix_sku").contains("ix_sku_name");
    }

    @Test
    void duplicateIndexRuleNeverSuggestsDroppingAUniqueIndex() {
        TableModel products = table(
                "products",
                List.of(),
                List.of(),
                List.of(),
                List.of(uniqueIndex("uq_sku", List.of("sku")), index("ix_sku_name", List.of("sku", "name"))));
        DatabaseAdvisorRuleResultDto result =
                new DuplicateIndexRule().evaluate(context(schema("ds", Dialect.GENERIC, List.of(products))));
        assertThat(result.status()).isEqualTo(PASS);
    }

    @Test
    void duplicateIndexRuleIgnoresPrefixPairsWithDifferentSemantics() {
        IndexModel hashed = new IndexModel(
                "ix_hash",
                List.of(IndexKeyPart.column("sku", true)),
                false,
                "hash",
                null,
                IndexModel.Visibility.VISIBLE,
                IndexModel.Validity.VALID);
        IndexModel btree = new IndexModel(
                "ix_btree",
                List.of(IndexKeyPart.column("sku", true), IndexKeyPart.column("name", true)),
                false,
                "btree",
                null,
                IndexModel.Visibility.VISIBLE,
                IndexModel.Validity.VALID);
        TableModel products = table("products", List.of(), List.of(), List.of(), List.of(hashed, btree));
        DatabaseAdvisorRuleResultDto result =
                new DuplicateIndexRule().evaluate(context(schema("ds", Dialect.GENERIC, List.of(products))));
        assertThat(result.status()).isEqualTo(PASS);
    }

    @Test
    void duplicateIndexRuleLeavesThePrimaryKeyBackingIndexAlone() {
        TableModel products = new TableModel(
                "app",
                "public",
                "products",
                "TABLE",
                List.of(column("id", "int8", Types.BIGINT)),
                "products_pkey",
                List.of("id"),
                List.of(),
                List.of(uniqueIndex("products_pkey", List.of("id")), index("ix_id_name", List.of("id", "name"))),
                false,
                false,
                false,
                TableMetadata.COMPLETE);
        DatabaseAdvisorRuleResultDto result =
                new DuplicateIndexRule().evaluate(context(schema("ds", Dialect.GENERIC, List.of(products))));
        assertThat(result.status()).isEqualTo(PASS);
    }

    // --- DB-SCHEMA-004: foreign key type mismatch ---

    @Test
    void foreignKeyTypeMismatchRuleComparesTheActualReferencedColumnNotThePrimaryKeyByPosition() {
        TableModel customers = table(
                "customers",
                List.of(column("id", "int8", Types.BIGINT), column("external_ref", "varchar", Types.VARCHAR, 64)),
                List.of("id"),
                List.of(),
                List.of());
        TableModel orders = table(
                "orders",
                List.of(column("customer_ref", "varchar", Types.VARCHAR, 64)),
                List.of(),
                List.of(foreignKey(
                        "fk_orders_customer", List.of("customer_ref"), "customers", List.of("external_ref"))),
                List.of());
        DatabaseAdvisorRuleResultDto result = new ForeignKeyTypeMismatchRule()
                .evaluate(context(schema("ds", Dialect.GENERIC, List.of(customers, orders))));
        assertThat(result.status()).isEqualTo(PASS);
    }

    @Test
    void foreignKeyTypeMismatchRuleFlagsANarrowerIntegerChildColumn() {
        TableModel customers =
                table("customers", List.of(column("id", "int8", Types.BIGINT)), List.of("id"), List.of(), List.of());
        TableModel orders = table(
                "orders",
                List.of(column("customer_id", "int4", Types.INTEGER)),
                List.of(),
                List.of(foreignKey("fk_orders_customer", List.of("customer_id"), "customers", List.of("id"))),
                List.of());
        DatabaseAdvisorRuleResultDto result = new ForeignKeyTypeMismatchRule()
                .evaluate(context(schema("ds", Dialect.GENERIC, List.of(customers, orders))));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("narrower integer type");
    }

    @Test
    void foreignKeyTypeMismatchRuleFlagsAShorterDeclaredLength() {
        TableModel customers = table(
                "customers",
                List.of(column("code", "varchar", Types.VARCHAR, 64)),
                List.of("code"),
                List.of(),
                List.of());
        TableModel orders = table(
                "orders",
                List.of(column("customer_code", "varchar", Types.VARCHAR, 32)),
                List.of(),
                List.of(foreignKey("fk_orders_customer", List.of("customer_code"), "customers", List.of("code"))),
                List.of());
        DatabaseAdvisorRuleResultDto result = new ForeignKeyTypeMismatchRule()
                .evaluate(context(schema("ds", Dialect.GENERIC, List.of(customers, orders))));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("shorter declared length");
    }

    @Test
    void foreignKeyTypeMismatchRuleTreatsAnIntervalColumnAsUnclassifiedRatherThanNumeric() {
        // "interval" contains "int": substring classification used to call it numeric and report a mismatch.
        TableModel windows =
                table("windows", List.of(column("id", "interval", Types.OTHER)), List.of("id"), List.of(), List.of());
        TableModel bookings = table(
                "bookings",
                List.of(column("window_id", "int4", Types.INTEGER)),
                List.of(),
                List.of(foreignKey("fk_bookings_window", List.of("window_id"), "windows", List.of("id"))),
                List.of());
        DatabaseAdvisorRuleResultDto result = new ForeignKeyTypeMismatchRule()
                .evaluate(context(schema("ds", Dialect.POSTGRESQL, List.of(windows, bookings))));
        assertThat(result.status()).isEqualTo(PASS);
    }

    @Test
    void foreignKeyTypeMismatchRuleFlagsEveryColumnOfACompositeForeignKey() {
        TableModel parents = table(
                "parents",
                List.of(column("tenant_id", "int8", Types.BIGINT), column("id", "int8", Types.BIGINT)),
                List.of("tenant_id", "id"),
                List.of(),
                List.of());
        TableModel children = table(
                "children",
                List.of(column("tenant_id", "int4", Types.INTEGER), column("parent_id", "int4", Types.INTEGER)),
                List.of(),
                List.of(foreignKey(
                        "fk_children_parent",
                        List.of("tenant_id", "parent_id"),
                        "parents",
                        List.of("tenant_id", "id"))),
                List.of());
        DatabaseAdvisorRuleResultDto result = new ForeignKeyTypeMismatchRule()
                .evaluate(context(schema("ds", Dialect.GENERIC, List.of(parents, children))));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.violationCount()).isEqualTo(2);
    }

    // --- DB-SCHEMA-005: redundant unique index duplicating the primary key ---

    @Test
    void redundantPrimaryKeyIndexRuleIgnoresThePrimaryKeysOwnBackingIndex() {
        TableModel accounts = new TableModel(
                "app",
                "public",
                "accounts",
                "TABLE",
                List.of(column("id", "int8", Types.BIGINT)),
                "accounts_pkey",
                List.of("id"),
                List.of(),
                List.of(uniqueIndex("accounts_pkey", List.of("id"))),
                false,
                false,
                false,
                TableMetadata.COMPLETE);
        DatabaseAdvisorRuleResultDto result = new RedundantPrimaryKeyUniqueIndexRule()
                .evaluate(context(schema("ds", Dialect.GENERIC, List.of(accounts))));
        assertThat(result.status()).isEqualTo(PASS);
    }

    @Test
    void redundantPrimaryKeyIndexRuleFlagsAnExtraUniqueIndexOnTheSameOrderedColumns() {
        TableModel accounts = new TableModel(
                "app",
                "public",
                "accounts",
                "TABLE",
                List.of(column("id", "int8", Types.BIGINT)),
                "accounts_pkey",
                List.of("id"),
                List.of(),
                List.of(uniqueIndex("accounts_pkey", List.of("id")), uniqueIndex("uq_accounts_id", List.of("id"))),
                false,
                false,
                false,
                TableMetadata.COMPLETE);
        DatabaseAdvisorRuleResultDto result = new RedundantPrimaryKeyUniqueIndexRule()
                .evaluate(context(schema("ds", Dialect.GENERIC, List.of(accounts))));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("uq_accounts_id");
        assertThat(result.sampleViolations().get(0)).doesNotContain("accounts_pkey ");
    }

    @Test
    void redundantPrimaryKeyIndexRuleRequiresTheSameColumnOrder() {
        TableModel memberships = new TableModel(
                "app",
                "public",
                "memberships",
                "TABLE",
                List.of(column("user_id", "int8", Types.BIGINT), column("group_id", "int8", Types.BIGINT)),
                "memberships_pkey",
                List.of("user_id", "group_id"),
                List.of(),
                List.of(
                        uniqueIndex("memberships_pkey", List.of("user_id", "group_id")),
                        uniqueIndex("uq_group_user", List.of("group_id", "user_id"))),
                false,
                false,
                false,
                TableMetadata.COMPLETE);
        DatabaseAdvisorRuleResultDto result = new RedundantPrimaryKeyUniqueIndexRule()
                .evaluate(context(schema("ds", Dialect.GENERIC, List.of(memberships))));
        assertThat(result.status()).isEqualTo(PASS);
    }
}
