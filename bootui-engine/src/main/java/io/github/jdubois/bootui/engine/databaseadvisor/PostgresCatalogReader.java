package io.github.jdubois.bootui.engine.databaseadvisor;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/**
 * The PostgreSQL-only, read-only {@code pg_catalog} augmentation the generic JDBC metadata API cannot answer:
 * broken indexes, index semantics (partial/expression/method/validity), declarative partitioning, extension
 * ownership, {@code NOT VALID} constraints, and sequence consumption against the owning column's capacity.
 *
 * <p>Every statement is a bounded {@code SELECT} against system catalogs — never application data, never
 * DDL — and every one of them is allowed to fail: a locked-down role that cannot read {@code pg_index} yields
 * a {@link VendorAugmentation.Status#FAILED} augmentation whose reason the matching rule reports as
 * {@code SKIPPED}, while the generic scan continues unaffected.</p>
 */
final class PostgresCatalogReader {

    private static final String SYSTEM_SCHEMA_FILTER =
            " and n.nspname not in ('pg_catalog', 'information_schema', 'pg_toast')"
                    + " and n.nspname not like 'pg\\_temp%' and n.nspname not like 'pg\\_toast%'";

    private static final String INVALID_INDEXES_SQL = """
            select n.nspname as schema_name, t.relname as table_name, c.relname as index_name,
                   i.indisvalid as is_valid, i.indisready as is_ready, i.indislive as is_live
            from pg_index i
            join pg_class c on c.oid = i.indexrelid
            join pg_class t on t.oid = i.indrelid
            join pg_namespace n on n.oid = t.relnamespace
            where (i.indisvalid = false or i.indisready = false or i.indislive = false)
              and c.relkind = 'i'
              and t.relkind <> 'p'
            """ + SYSTEM_SCHEMA_FILTER + """
              and not exists (
                    select 1 from pg_depend d
                    where d.classid = 'pg_class'::regclass and d.objid = c.oid
                      and d.refclassid = 'pg_extension'::regclass and d.deptype = 'e')
            order by n.nspname, t.relname, c.relname
            limit ?
            """;

    private static final String INDEX_DETAILS_SQL = """
            select n.nspname as schema_name, t.relname as table_name, c.relname as index_name,
                   i.indisvalid as is_valid,
                   (i.indpred is not null) as is_partial,
                   pg_get_expr(i.indpred, i.indrelid) as predicate,
                   (i.indexprs is not null) as has_expression,
                   am.amname as method
            from pg_index i
            join pg_class c on c.oid = i.indexrelid
            join pg_class t on t.oid = i.indrelid
            join pg_namespace n on n.oid = t.relnamespace
            join pg_am am on am.oid = c.relam
            where true
            """ + SYSTEM_SCHEMA_FILTER + """
            order by n.nspname, t.relname, c.relname
            limit ?
            """;

    private static final String PARTITIONS_SQL = """
            select n.nspname as schema_name, c.relname as table_name,
                   (c.relkind = 'p') as is_partitioned_parent,
                   c.relispartition as is_partition_child
            from pg_class c
            join pg_namespace n on n.oid = c.relnamespace
            where c.relkind in ('r', 'p')
              and (c.relkind = 'p' or c.relispartition)
            """ + SYSTEM_SCHEMA_FILTER + """
            order by n.nspname, c.relname
            limit ?
            """;

    private static final String EXTENSION_TABLES_SQL = """
            select n.nspname as schema_name, c.relname as table_name, e.extname as extension_name
            from pg_depend d
            join pg_class c on c.oid = d.objid
            join pg_namespace n on n.oid = c.relnamespace
            join pg_extension e on e.oid = d.refobjid
            where d.classid = 'pg_class'::regclass
              and d.refclassid = 'pg_extension'::regclass
              and d.deptype = 'e'
              and c.relkind in ('r', 'p')
            """ + SYSTEM_SCHEMA_FILTER + """
            order by n.nspname, c.relname
            limit ?
            """;

    private static final String UNVALIDATED_CONSTRAINTS_SQL = """
            select n.nspname as schema_name, t.relname as table_name, c.conname as constraint_name,
                   c.contype as constraint_type, pg_get_constraintdef(c.oid) as definition
            from pg_constraint c
            join pg_class t on t.oid = c.conrelid
            join pg_namespace n on n.oid = t.relnamespace
            where c.convalidated = false
              and c.contype in ('f', 'c')
            """ + SYSTEM_SCHEMA_FILTER + """
              and not exists (
                    select 1 from pg_depend d
                    where d.classid = 'pg_constraint'::regclass and d.objid = c.oid
                      and d.refclassid = 'pg_extension'::regclass and d.deptype = 'e')
            order by n.nspname, t.relname, c.conname
            limit ?
            """;

    private static final String SEQUENCES_SQL = """
            select s.schemaname as schema_name, s.sequencename as sequence_name,
                   s.last_value as last_value, s.max_value as max_value, s.cycle as is_cycle,
                   owner_ns.nspname as owner_schema, owner_table.relname as owner_table,
                   owner_column.attname as owner_column, owner_type.typname as owner_type
            from pg_sequences s
            join pg_class seq on seq.relname = s.sequencename
            join pg_namespace n on n.oid = seq.relnamespace and n.nspname = s.schemaname
            left join pg_depend d on d.classid = 'pg_class'::regclass and d.objid = seq.oid
                 and d.refclassid = 'pg_class'::regclass and d.deptype in ('a', 'i')
            left join pg_class owner_table on owner_table.oid = d.refobjid
            left join pg_namespace owner_ns on owner_ns.oid = owner_table.relnamespace
            left join pg_attribute owner_column on owner_column.attrelid = d.refobjid
                 and owner_column.attnum = d.refobjsubid
            left join pg_type owner_type on owner_type.oid = owner_column.atttypid
            where s.last_value is not null
              and seq.relkind = 'S'
            """ + SYSTEM_SCHEMA_FILTER + """
            order by s.schemaname, s.sequencename
            limit ?
            """;

    private PostgresCatalogReader() {}

    static void read(
            Connection connection,
            DatabaseVersion version,
            DialectCapabilities capabilities,
            ScanBudget budget,
            DatabaseAdvisorLimits limits,
            VendorFindings.Builder findings) {
        findings.add(CatalogQuery.read(
                connection,
                VendorFindingKinds.POSTGRES_INVALID_INDEXES,
                INVALID_INDEXES_SQL,
                budget,
                limits,
                PostgresCatalogReader::readInvalidIndex));
        findings.add(CatalogQuery.read(
                connection,
                VendorFindingKinds.POSTGRES_INDEX_DETAILS,
                INDEX_DETAILS_SQL,
                budget,
                limits,
                PostgresCatalogReader::readIndexDetail));
        findings.add(CatalogQuery.read(
                connection,
                VendorFindingKinds.POSTGRES_EXTENSION_TABLES,
                EXTENSION_TABLES_SQL,
                budget,
                limits,
                PostgresCatalogReader::readExtensionTable));
        findings.add(CatalogQuery.read(
                connection,
                VendorFindingKinds.POSTGRES_UNVALIDATED_CONSTRAINTS,
                UNVALIDATED_CONSTRAINTS_SQL,
                budget,
                limits,
                PostgresCatalogReader::readUnvalidatedConstraint));
        if (capabilities.declarativePartitioning()) {
            findings.add(CatalogQuery.read(
                    connection,
                    VendorFindingKinds.POSTGRES_PARTITIONS,
                    PARTITIONS_SQL,
                    budget,
                    limits,
                    PostgresCatalogReader::readPartition));
        } else {
            findings.add(VendorAugmentation.notApplicable(
                    VendorFindingKinds.POSTGRES_PARTITIONS,
                    "Declarative partitioning requires PostgreSQL 10 or later (server reports " + version.describe()
                            + ")."));
        }
        if (capabilities.sequencesView()) {
            findings.add(CatalogQuery.read(
                    connection,
                    VendorFindingKinds.POSTGRES_SEQUENCES,
                    SEQUENCES_SQL,
                    budget,
                    limits,
                    PostgresCatalogReader::readSequence));
        } else {
            findings.add(VendorAugmentation.notApplicable(
                    VendorFindingKinds.POSTGRES_SEQUENCES,
                    "The pg_sequences view requires PostgreSQL 10 or later (server reports " + version.describe()
                            + ")."));
        }
    }

    private static PostgresInvalidIndex readInvalidIndex(ResultSet rs) throws SQLException {
        return new PostgresInvalidIndex(
                rs.getString("schema_name"),
                rs.getString("table_name"),
                rs.getString("index_name"),
                rs.getBoolean("is_valid"),
                rs.getBoolean("is_ready"),
                rs.getBoolean("is_live"));
    }

    private static PostgresIndexDetail readIndexDetail(ResultSet rs) throws SQLException {
        return new PostgresIndexDetail(
                rs.getString("schema_name"),
                rs.getString("table_name"),
                rs.getString("index_name"),
                rs.getBoolean("is_valid"),
                rs.getBoolean("is_partial"),
                rs.getString("predicate"),
                rs.getBoolean("has_expression"),
                rs.getString("method"));
    }

    private static PostgresPartitionInfo readPartition(ResultSet rs) throws SQLException {
        return new PostgresPartitionInfo(
                rs.getString("schema_name"),
                rs.getString("table_name"),
                rs.getBoolean("is_partitioned_parent"),
                rs.getBoolean("is_partition_child"));
    }

    private static PostgresExtensionTable readExtensionTable(ResultSet rs) throws SQLException {
        return new PostgresExtensionTable(
                rs.getString("schema_name"), rs.getString("table_name"), rs.getString("extension_name"));
    }

    private static PostgresUnvalidatedConstraint readUnvalidatedConstraint(ResultSet rs) throws SQLException {
        return new PostgresUnvalidatedConstraint(
                rs.getString("schema_name"),
                rs.getString("table_name"),
                rs.getString("constraint_name"),
                rs.getString("constraint_type"),
                rs.getString("definition"));
    }

    private static PostgresSequenceUsage readSequence(ResultSet rs) throws SQLException {
        BigInteger lastValue = bigInteger(rs, "last_value");
        if (lastValue == null) {
            // pg_sequences hides last_value from roles without SELECT/USAGE on the sequence; a sequence whose
            // consumption cannot be read is skipped rather than reported as unused.
            return null;
        }
        String ownerType = rs.getString("owner_type");
        return new PostgresSequenceUsage(
                rs.getString("schema_name"),
                rs.getString("sequence_name"),
                lastValue,
                bigInteger(rs, "max_value"),
                capacityOf(ownerType),
                rs.getBoolean("is_cycle"),
                rs.getString("owner_schema"),
                rs.getString("owner_table"),
                rs.getString("owner_column"),
                ownerType);
    }

    /** The largest value the sequence's owning column type can hold, or {@code null} when not classified. */
    private static BigInteger capacityOf(String pgTypeName) {
        if (pgTypeName == null) {
            return null;
        }
        return switch (pgTypeName.toLowerCase(Locale.ROOT)) {
            case "int2", "smallint", "smallserial" -> BigInteger.valueOf(Short.MAX_VALUE);
            case "int4", "integer", "serial" -> BigInteger.valueOf(Integer.MAX_VALUE);
            case "int8", "bigint", "bigserial" -> BigInteger.valueOf(Long.MAX_VALUE);
            default -> null;
        };
    }

    private static BigInteger bigInteger(ResultSet rs, String column) throws SQLException {
        java.math.BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.toBigInteger();
    }
}
