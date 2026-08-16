package io.github.jdubois.bootui.engine.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaIntrospectorTests {

    private static DatabaseAdvisorLimits limits() {
        return DatabaseAdvisorLimits.DEFAULTS;
    }

    private static ScanBudget budget() {
        return ScanBudget.of(Duration.ofSeconds(30));
    }

    @Test
    void readForeignKeysGroupsAnUnnamedCompositeForeignKeyIntoOneConstraint() throws Exception {
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        ResultSet rs = mock(ResultSet.class);
        when(metaData.getImportedKeys(any(), any(), any())).thenReturn(rs);
        // Simulates a driver reporting an unnamed composite foreign key (child.a, child.b) -> parent(a, b)
        // followed by a second, unrelated unnamed single-column foreign key on the same table.
        when(rs.next()).thenReturn(true, true, true, false);
        when(rs.getString("FK_NAME")).thenReturn(null, null, null);
        when(rs.getShort("KEY_SEQ")).thenReturn((short) 1, (short) 2, (short) 1);
        when(rs.getString("FKCOLUMN_NAME")).thenReturn("A", "B", "C");
        when(rs.getString("PKCOLUMN_NAME")).thenReturn("A", "B", "ID");
        when(rs.getString("PKTABLE_NAME")).thenReturn("parent", "parent", "other");
        when(rs.getString("PKTABLE_SCHEM")).thenReturn("public", "public", "public");

        List<ForeignKeyModel> foreignKeys = SchemaIntrospector.readForeignKeys(metaData, "cat", "schema", "child");

        assertThat(foreignKeys).hasSize(2);
        assertThat(foreignKeys.get(0).columns()).containsExactly("A", "B");
        assertThat(foreignKeys.get(0).referencedColumns()).containsExactly("A", "B");
        assertThat(foreignKeys.get(0).referencedTable()).isEqualTo("parent");
        assertThat(foreignKeys.get(0).referencedSchema()).isEqualTo("public");
        assertThat(foreignKeys.get(1).columns()).containsExactly("C");
        assertThat(foreignKeys.get(1).referencedColumns()).containsExactly("ID");
        assertThat(foreignKeys.get(1).referencedTable()).isEqualTo("other");
    }

    @Test
    void readForeignKeysKeepsNamedForeignKeysGroupedByNameAndCarriesTheReferencedColumns() throws Exception {
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        ResultSet rs = mock(ResultSet.class);
        when(metaData.getImportedKeys(any(), any(), any())).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString("FK_NAME")).thenReturn("fk_child_parent", "fk_child_parent");
        when(rs.getShort("KEY_SEQ")).thenReturn((short) 1, (short) 2);
        when(rs.getString("FKCOLUMN_NAME")).thenReturn("A", "B");
        when(rs.getString("PKCOLUMN_NAME")).thenReturn("TENANT_ID", "ID");
        when(rs.getString("PKTABLE_NAME")).thenReturn("parent", "parent");

        List<ForeignKeyModel> foreignKeys = SchemaIntrospector.readForeignKeys(metaData, "cat", "schema", "child");

        assertThat(foreignKeys).hasSize(1);
        assertThat(foreignKeys.get(0).name()).isEqualTo("fk_child_parent");
        assertThat(foreignKeys.get(0).columns()).containsExactly("A", "B");
        assertThat(foreignKeys.get(0).referencedColumns()).containsExactly("TENANT_ID", "ID");
        assertThat(foreignKeys.get(0).consistent()).isTrue();
    }

    @Test
    void introspectRestoresTheConnectionsOriginalReadOnlyState() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isReadOnly()).thenReturn(false);
        when(connection.getMetaData()).thenThrow(new SQLException("metadata unavailable"));

        SchemaSnapshot snapshot = SchemaIntrospector.introspect("primary", () -> connection, budget(), limits());

        assertThat(snapshot.available()).isFalse();
        verify(connection).setReadOnly(true);
        verify(connection).setReadOnly(false);
    }

    @Test
    void introspectLeavesAnAlreadyReadOnlyConnectionAlone() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isReadOnly()).thenReturn(true);
        when(connection.getMetaData()).thenThrow(new SQLException("metadata unavailable"));

        SchemaIntrospector.introspect("primary", () -> connection, budget(), limits());

        verify(connection, never()).setReadOnly(false);
    }

    @Test
    void introspectRedactsCredentialsFromAConnectionFailure() {
        SchemaSnapshot snapshot = SchemaIntrospector.introspect(
                "primary",
                () -> {
                    throw new SQLException("FATAL: password authentication failed for "
                            + "jdbc:postgresql://app:sup3rs3cret@db.internal:5432/orders");
                },
                budget(),
                limits());

        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.error()).doesNotContain("sup3rs3cret").contains("******@db.internal");
        assertThat(snapshot.diagnostics()).hasSize(1);
        assertThat(snapshot.diagnostics().get(0).level()).isEqualTo(SchemaDiagnostic.ERROR);
    }

    @Test
    void introspectFailsFastWhenTheScanBudgetIsAlreadySpent() {
        ScanBudget spent = ScanBudget.of(Duration.ZERO, () -> 0L);
        SchemaSnapshot snapshot = SchemaIntrospector.introspect(
                "primary",
                () -> {
                    throw new IllegalStateException("the datasource must never be touched");
                },
                spent,
                limits());

        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.error()).contains("scan budget ran out");
    }

    @Test
    void introspectReportsAMissingDataSourceRatherThanThrowing() {
        SchemaSnapshot snapshot = SchemaIntrospector.introspect("primary", (javax.sql.DataSource) null);
        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.error()).contains("not available");
    }
}
