package io.github.jdubois.bootui.engine.databaseadvisor;

import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.column;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.foreignKey;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.index;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.notNullColumn;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.prefixIndex;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.schema;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.table;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.uniqueIndex;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedColumnFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedForeignKeyFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedUniqueConstraintFacts;
import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Hibernate ↔ physical schema cross-reference rules (DB-HIB-001..007). */
class DatabaseAdvisorHibernateRulesTests {

    private static final String PASS = DatabaseAdvisorRuleSupport.PASS;
    private static final String VIOLATION = DatabaseAdvisorRuleSupport.VIOLATION;
    private static final String SKIPPED = DatabaseAdvisorRuleSupport.SKIPPED;

    private static DatabaseAdvisorContext hibernateContext(
            List<SchemaSnapshot> schemas, List<MappedEntityFacts> entities) {
        return new DatabaseAdvisorContext(schemas, true, entities);
    }

    private static DatabaseAdvisorContext hibernateContext(SchemaSnapshot schema, MappedEntityFacts entity) {
        return hibernateContext(List.of(schema), List.of(entity));
    }

    private static MappedEntityFacts entity(
            String name,
            String tableName,
            List<MappedForeignKeyFacts> foreignKeys,
            List<MappedColumnFacts> columns,
            List<MappedUniqueConstraintFacts> uniqueConstraints) {
        return new MappedEntityFacts(name, tableName, null, null, foreignKeys, columns, uniqueConstraints);
    }

    // --- DB-HIB-001: mapped foreign key without a usable index ---

    @Test
    void hibernateForeignKeyIndexRuleSkipsWithoutAMetamodel() {
        DatabaseAdvisorRuleResultDto result = new HibernateMissingForeignKeyIndexRule()
                .evaluate(DatabaseAdvisorFixtures.context(schema("ds", Dialect.GENERIC, List.of())));
        assertThat(result.status()).isEqualTo(SKIPPED);
    }

    @Test
    void hibernateForeignKeyIndexRuleHandlesCompositeJoinColumns() {
        TableModel orderLines = table(
                "order_lines",
                List.of(column("tenant_id", "int8", Types.BIGINT), column("order_id", "int8", Types.BIGINT)),
                List.of(),
                List.of(),
                List.of(index("ix_tenant", List.of("tenant_id"))));
        MappedEntityFacts entity = entity(
                "com.example.OrderLine",
                "order_lines",
                List.of(new MappedForeignKeyFacts("com.example.OrderLine#order", List.of("tenant_id", "order_id"))),
                List.of(),
                List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateMissingForeignKeyIndexRule()
                .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(orderLines)), entity));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("tenant_id, order_id");
    }

    @Test
    void hibernateForeignKeyIndexRulePassesWhenTheWholeKeyLeadsAUsableIndex() {
        TableModel orderLines = table(
                "order_lines",
                List.of(column("tenant_id", "int8", Types.BIGINT), column("order_id", "int8", Types.BIGINT)),
                List.of(),
                List.of(),
                List.of(index("ix_tenant_order", List.of("tenant_id", "order_id"))));
        MappedEntityFacts entity = entity(
                "com.example.OrderLine",
                "order_lines",
                List.of(new MappedForeignKeyFacts("com.example.OrderLine#order", List.of("tenant_id", "order_id"))),
                List.of(),
                List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateMissingForeignKeyIndexRule()
                .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(orderLines)), entity));
        assertThat(result.status()).isEqualTo(PASS);
    }

    // --- DB-HIB-002: mapped table missing ---

    @Test
    void hibernateMissingTableRuleReportsAnAbsentMappedTable() {
        MappedEntityFacts entity = entity("com.example.Ghost", "ghosts", List.of(), List.of(), List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateMissingTableRule()
                .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of()), entity));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("ghosts");
    }

    @Test
    void hibernateMissingTableRuleSkipsAmbiguousMatchesAcrossDatasources() {
        TableModel orders = table("orders", List.of(), List.of("id"), List.of(), List.of());
        MappedEntityFacts entity = entity("com.example.Order", "orders", List.of(), List.of(), List.of());
        DatabaseAdvisorContext context = hibernateContext(
                List.of(
                        schema("primary", Dialect.GENERIC, List.of(orders)),
                        schema("reporting", Dialect.GENERIC, List.of(orders))),
                List.of(entity));
        assertThat(new HibernateMissingTableRule().evaluate(context).status()).isEqualTo(PASS);
    }

    @Test
    void hibernateMissingTableRuleSkipsWhenTheTableListWasTruncated() {
        SchemaSnapshot truncated = new SchemaSnapshot(
                "ds",
                Dialect.GENERIC,
                "H2",
                DatabaseVersion.UNKNOWN,
                "UPPER",
                List.of(),
                VendorFindings.EMPTY,
                List.of(),
                true,
                null);
        MappedEntityFacts entity = entity("com.example.Order", "orders", List.of(), List.of(), List.of());
        DatabaseAdvisorRuleResultDto result =
                new HibernateMissingTableRule().evaluate(hibernateContext(truncated, entity));
        assertThat(result.status()).isEqualTo(SKIPPED);
        assertThat(result.sampleViolations().get(0)).contains("truncated");
    }

    @Test
    void hibernateMissingTableRuleHonorsTheDeclaredSchema() {
        TableModel orders = TableModel.of("app", "public", "orders", List.of(), List.of("id"), List.of(), List.of());
        MappedEntityFacts entity = new MappedEntityFacts(
                "com.example.Order", "orders", "reporting", null, List.of(), List.of(), List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateMissingTableRule()
                .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(orders)), entity));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("reporting.orders");
    }

    // --- DB-HIB-003: type/nullability mismatch ---

    @Test
    void hibernateColumnMismatchRuleOnlyComparesExplicitNullability() {
        TableModel users = table(
                "users", List.of(notNullColumn("email", "varchar", Types.VARCHAR)), List.of(), List.of(), List.of());
        MappedEntityFacts undeclared = entity(
                "com.example.User",
                "users",
                List.of(),
                List.of(new MappedColumnFacts("com.example.User#email", "email", null, "String")),
                List.of());
        assertThat(new HibernateColumnMismatchRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(users)), undeclared))
                        .status())
                .isEqualTo(PASS);

        MappedEntityFacts declaredNullable = entity(
                "com.example.User",
                "users",
                List.of(),
                List.of(new MappedColumnFacts("com.example.User#email", "email", true, "String")),
                List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateColumnMismatchRule()
                .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(users)), declaredNullable));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("explicitly mapped as nullable");
    }

    @Test
    void hibernateColumnMismatchRuleSkipsConverterAndEnumMappings() {
        TableModel users =
                table("users", List.of(column("status", "int4", Types.INTEGER)), List.of(), List.of(), List.of());
        MappedEntityFacts entity = entity(
                "com.example.User",
                "users",
                List.of(),
                List.of(new MappedColumnFacts(
                        "com.example.User#status", "status", null, "Status", null, false, true, false)),
                List.of());
        assertThat(new HibernateColumnMismatchRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(users)), entity))
                        .status())
                .isEqualTo(PASS);
    }

    // --- DB-HIB-004: mapped length longer than the physical column ---

    @Test
    void hibernateColumnLengthRuleOnlyComparesExplicitlyDeclaredLengths() {
        TableModel users = table(
                "users", List.of(column("nickname", "varchar", Types.VARCHAR, 50)), List.of(), List.of(), List.of());
        MappedEntityFacts undeclared = entity(
                "com.example.User",
                "users",
                List.of(),
                List.of(new MappedColumnFacts("com.example.User#nickname", "nickname", null, "String")),
                List.of());
        assertThat(new HibernateColumnLengthMismatchRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(users)), undeclared))
                        .status())
                .isEqualTo(PASS);

        MappedEntityFacts declared = entity(
                "com.example.User",
                "users",
                List.of(),
                List.of(new MappedColumnFacts("com.example.User#nickname", "nickname", null, "String", 120)),
                List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateColumnLengthMismatchRule()
                .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(users)), declared));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("length=120").contains("varchar(50)");
    }

    @Test
    void hibernateColumnLengthRuleSkipsUnboundedTextColumnsAndLobs() {
        TableModel documents = table(
                "documents",
                List.of(column("body", "text", Types.VARCHAR, Integer.MAX_VALUE)),
                List.of(),
                List.of(),
                List.of());
        MappedEntityFacts entity = entity(
                "com.example.Document",
                "documents",
                List.of(),
                List.of(new MappedColumnFacts(
                        "com.example.Document#body", "body", null, "String", 4000, true, true, false)),
                List.of());
        assertThat(new HibernateColumnLengthMismatchRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(documents)), entity))
                        .status())
                .isEqualTo(PASS);
    }

    // --- DB-HIB-005: unique constraint coverage ---

    @Test
    void hibernateUniqueIndexRuleRejectsPrefixUniquenessAsCoverage() {
        TableModel users = table(
                "users",
                List.of(column("email", "varchar", Types.VARCHAR, 255)),
                List.of(),
                List.of(),
                List.of(prefixIndex("uq_email_prefix", "email", 20, true)));
        MappedEntityFacts entity = entity(
                "com.example.User",
                "users",
                List.of(),
                List.of(),
                List.of(new MappedUniqueConstraintFacts("com.example.User#email", List.of("email"))));
        DatabaseAdvisorRuleResultDto result = new HibernateMissingUniqueIndexRule()
                .evaluate(hibernateContext(schema("ds", Dialect.MYSQL, List.of(users)), entity));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("no physical unique index fully enforces");
    }

    @Test
    void hibernateUniqueIndexRuleIgnoresColumnOrderForCompositeUniqueness() {
        TableModel memberships = table(
                "memberships",
                List.of(column("user_id", "int8", Types.BIGINT), column("group_id", "int8", Types.BIGINT)),
                List.of(),
                List.of(),
                List.of(uniqueIndex("uq_group_user", List.of("group_id", "user_id"))));
        MappedEntityFacts entity = entity(
                "com.example.Membership",
                "memberships",
                List.of(),
                List.of(),
                List.of(new MappedUniqueConstraintFacts(
                        "com.example.Membership @Table unique constraint", List.of("user_id", "group_id"))));
        assertThat(new HibernateMissingUniqueIndexRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(memberships)), entity))
                        .status())
                .isEqualTo(PASS);
    }

    // --- DB-HIB-006: mapped column missing physically ---

    @Test
    void hibernateMissingColumnRuleReportsMappedColumnsAndJoinColumnsThatDoNotExist() {
        TableModel users =
                table("users", List.of(column("id", "int8", Types.BIGINT)), List.of("id"), List.of(), List.of());
        MappedEntityFacts entity = entity(
                "com.example.User",
                "users",
                List.of(new MappedForeignKeyFacts("com.example.User#team", List.of("team_id"))),
                List.of(new MappedColumnFacts("com.example.User#nickname", "nickname", null, "String")),
                List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateMissingColumnRule()
                .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(users)), entity));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations().get(0)).contains("users.nickname");
        assertThat(result.sampleViolations().get(1)).contains("users.team_id");
    }

    @Test
    void hibernateMissingColumnRuleSkipsTablesWithIncompleteColumnMetadata() {
        TableModel truncated = new TableModel(
                "app",
                "public",
                "users",
                "TABLE",
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                false,
                new TableMetadata(true, true, true, true, true, List.of("only the first 300 columns were read")));
        MappedEntityFacts entity = entity(
                "com.example.User",
                "users",
                List.of(),
                List.of(new MappedColumnFacts("com.example.User#nickname", "nickname", null, "String")),
                List.of());
        assertThat(new HibernateMissingColumnRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(truncated)), entity))
                        .status())
                .isEqualTo(PASS);
    }

    // --- DB-HIB-007: mapped association without a physical foreign key ---

    @Test
    void hibernateMissingForeignKeyConstraintRuleReportsAnUnenforcedAssociation() {
        TableModel orders = table(
                "orders",
                List.of(column("customer_id", "int8", Types.BIGINT)),
                List.of(),
                List.of(),
                List.of(index("ix_customer", List.of("customer_id"))));
        MappedEntityFacts entity = entity(
                "com.example.Order",
                "orders",
                List.of(new MappedForeignKeyFacts("com.example.Order#customer", List.of("customer_id"))),
                List.of(),
                List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateMissingForeignKeyConstraintRule()
                .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(orders)), entity));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("enforces no such constraint");
    }

    @Test
    void hibernateMissingForeignKeyConstraintRulePassesWhenTheConstraintExists() {
        TableModel orders = table(
                "orders",
                List.of(column("customer_id", "int8", Types.BIGINT)),
                List.of(),
                List.of(foreignKey("fk_orders_customer", List.of("customer_id"), "customers", List.of("id"))),
                List.of());
        MappedEntityFacts entity = entity(
                "com.example.Order",
                "orders",
                List.of(new MappedForeignKeyFacts("com.example.Order#customer", List.of("customer_id"))),
                List.of(),
                List.of());
        assertThat(new HibernateMissingForeignKeyConstraintRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(orders)), entity))
                        .status())
                .isEqualTo(PASS);
    }
}
