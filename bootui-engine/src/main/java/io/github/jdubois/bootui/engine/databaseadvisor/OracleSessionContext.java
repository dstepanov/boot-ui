package io.github.jdubois.bootui.engine.databaseadvisor;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The Oracle session facts the advisor needs before it can safely scope any {@code ALL_*} catalog query.
 *
 * <p>{@code currentSchema} is read through {@code SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')} rather than
 * assumed to be the connecting user: {@code ALTER SESSION SET CURRENT_SCHEMA} can point a session at a
 * different owner without a privilege check, and every {@code ALL_*} query this advisor runs is scoped to it
 * with {@code OWNER = ?} — never left unscoped, which would silently widen a scan to every schema the
 * connected user merely has {@code SELECT ANY} visibility into.</p>
 *
 * <p>{@code containerName}/{@code containerId} (the current pluggable database) are read the same way and
 * used only for internal diagnostics: every catalog query here already reads only {@code ALL_*} views, which
 * are container-local by nature, so the PDB is never queried across explicitly — recording it just makes a
 * diagnostic message unambiguous on a multitenant server where two PDBs can have same-named schemas.</p>
 *
 * <p>{@code SYS_CONTEXT('USERENV', ...)} requires no elevated privilege: it is available to any session,
 * unlike the {@code ALL_*}/{@code DBA_*} split this advisor is otherwise careful to stay on the {@code ALL_*}
 * side of.</p>
 */
record OracleSessionContext(String currentSchema, String containerName, String containerId) {

    private static final String SESSION_CONTEXT_SQL = "select"
            + " sys_context('USERENV', 'CURRENT_SCHEMA') as current_schema,"
            + " sys_context('USERENV', 'CON_NAME') as container_name,"
            + " sys_context('USERENV', 'CON_ID') as container_id"
            + " from dual";

    static OracleSessionContext read(Connection connection, ScanBudget budget, DatabaseAdvisorLimits limits)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(budget.remainingSecondsAtMost(limits.statementTimeoutSeconds()));
            try (ResultSet rs = statement.executeQuery(SESSION_CONTEXT_SQL)) {
                if (!rs.next()) {
                    return new OracleSessionContext(null, null, null);
                }
                return new OracleSessionContext(
                        rs.getString("current_schema"), rs.getString("container_name"), rs.getString("container_id"));
            }
        }
    }
}
