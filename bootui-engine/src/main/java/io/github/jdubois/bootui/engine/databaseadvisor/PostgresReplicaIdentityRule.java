package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL-specific: a table actually in scope for logical replication — an explicit
 * {@code pg_publication_rel} member, or implicitly included because some publication is declared
 * {@code FOR ALL TABLES} — with no usable replica identity. {@code UPDATE}/{@code DELETE} against such a
 * table fails outright once a subscriber attaches: {@code "cannot update/delete from table ... because it
 * does not have a replica identity and publishes updates or deletes"}.
 *
 * <p>Applicability is deliberately narrow: a table is only a candidate here when it is genuinely reachable
 * through a publication. Flagging every primary-key-less table for a replication feature the database may not
 * even have configured would be noise on the overwhelming majority of development databases, which use no
 * logical replication at all — that is why this is a dedicated rule rather than a variant of {@code
 * DB-SCHEMA-001}.</p>
 *
 * <p>{@code pg_class.relreplident} is only unusable in two shapes: {@code n} (explicitly {@code NOTHING}), or
 * the default ({@code d}) on a table with no primary key, which silently degrades to the same thing. {@code f}
 * (full row) and {@code i} (a specific unique index) are always usable and never flagged.</p>
 */
final class PostgresReplicaIdentityRule extends AbstractDatabaseAdvisorRule {

    PostgresReplicaIdentityRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-PG-004",
                "PostgreSQL table lacking usable replica identity",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Detects a table reachable through a publication (pg_publication_rel, or any FOR ALL TABLES "
                        + "publication) whose pg_class.relreplident is NOTHING, or DEFAULT with no primary key — "
                        + "both of which resolve to no usable replica identity.",
                "Add a primary key (restores the DEFAULT replica identity), or set one explicitly with ALTER "
                        + "TABLE ... REPLICA IDENTITY FULL/USING INDEX .... Without a usable replica identity, "
                        + "UPDATE/DELETE against this table fails outright once a logical replication subscriber "
                        + "attaches.",
                "https://www.postgresql.org/docs/current/sql-altertable.html#SQL-ALTERTABLE-REPLICA-IDENTITY"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> schemas = context.schemasOf(Dialect.POSTGRESQL);
        String skipReason = VendorRuleSupport.skipReason(
                schemas,
                VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES,
                "No PostgreSQL datasource was detected.");
        if (skipReason != null) {
            return skipped(skipReason);
        }
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : schemas) {
            if (!VendorRuleSupport.available(schema, VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES)) {
                continue;
            }
            for (PostgresReplicaIdentityCandidate candidate :
                    schema.vendorFindings().findings(VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES)) {
                checkCandidate(schema, candidate, details);
            }
        }
        return violation(details);
    }

    private void checkCandidate(
            SchemaSnapshot schema, PostgresReplicaIdentityCandidate candidate, List<String> details) {
        TableModel table = schema.table(null, candidate.schema(), candidate.table());
        if (table == null
                || table.extensionOwned()
                || table.partitionChild()
                || !table.metadata().primaryKeyRead()) {
            // Not one of our own analyzable tables (unreadable, extension-owned, or a partition child whose
            // structure is analyzed through its parent), or the candidate resolved to no known table at all.
            return;
        }
        boolean usable = !candidate.nothing()
                && (!candidate.usesDefault() || !table.primaryKeyColumns().isEmpty());
        if (usable) {
            return;
        }
        String reason = candidate.nothing()
                ? "REPLICA IDENTITY is explicitly NOTHING"
                : "REPLICA IDENTITY is DEFAULT but the table has no primary key";
        details.add(schema.dataSourceName() + ": " + candidate.qualifiedTable() + " is published for logical "
                + "replication, but " + reason + ", so it has no usable replica identity.");
    }
}
