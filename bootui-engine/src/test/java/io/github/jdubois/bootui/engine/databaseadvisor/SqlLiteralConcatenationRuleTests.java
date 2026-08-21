package io.github.jdubois.bootui.engine.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.engine.support.DetailText;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The concatenated-literal rule is the one Database Advisor check that reads runtime SQL rather than schema
 * metadata, so these tests care as much about what it refuses to report as about what it reports.
 */
class SqlLiteralConcatenationRuleTests {

    private final SqlLiteralConcatenationRule rule = new SqlLiteralConcatenationRule();

    private static SqlTraceEntryDto statement(String sql) {
        return statement(sql, "PREPARED");
    }

    private static SqlTraceEntryDto statement(String sql, String statementType) {
        return new SqlTraceEntryDto(
                1L,
                1_000L,
                sql,
                statementType,
                "SELECT",
                5L,
                true,
                null,
                null,
                0,
                "conn-1",
                "exec-1",
                false,
                List.of(),
                null,
                null);
    }

    private DatabaseAdvisorRuleResultDto evaluate(List<SqlTraceEntryDto> statements) {
        return rule.evaluate(new DatabaseAdvisorContext(List.of(), false, List.of(), statements));
    }

    @Test
    void skipsWhenNoStatementsHaveBeenCaptured() {
        DatabaseAdvisorRuleResultDto result = evaluate(List.of());

        assertThat(result.status()).isEqualTo(DatabaseAdvisorRuleSupport.SKIPPED);
        assertThat(result.sampleViolations().toString()).contains("Enable SQL Trace");
    }

    @Test
    void reportsAShapeWhoseFilteringLiteralChangesBetweenExecutions() {
        DatabaseAdvisorRuleResultDto result = evaluate(List.of(
                statement("select * from orders where customer_id = 17"),
                statement("select * from orders where customer_id = 42"),
                statement("select * from orders where customer_id = 91")));

        assertThat(result.status()).isEqualTo(DatabaseAdvisorRuleSupport.VIOLATION);
        assertThat(result.sampleViolations()).hasSize(1);
        assertThat(result.sampleViolations().get(0)).contains("select * from orders where customer_id = ?");
        assertThat(result.sampleViolations().get(0)).contains("3 distinct texts");
    }

    @Test
    void neverEchoesTheConcatenatedValuesItFoundBackIntoTheReport() {
        DatabaseAdvisorRuleResultDto result = evaluate(List.of(
                statement("select * from users where token = 'secret-token-aaa'"),
                statement("select * from users where token = 'secret-token-bbb'")));

        assertThat(result.status()).isEqualTo(DatabaseAdvisorRuleSupport.VIOLATION);
        assertThat(result.sampleViolations().toString()).doesNotContain("secret-token");
        assertThat(result.description()).doesNotContain("secret-token");
        assertThat(result.recommendation()).doesNotContain("secret-token");
    }

    @Test
    void callsTwoVariantsSuggestiveAndThreeStrong() {
        String twoVariants = evaluate(
                        List.of(statement("select * from a where id = 1"), statement("select * from a where id = 2")))
                .sampleViolations()
                .get(0);
        String threeVariants = evaluate(List.of(
                        statement("select * from a where id = 1"),
                        statement("select * from a where id = 2"),
                        statement("select * from a where id = 3")))
                .sampleViolations()
                .get(0);

        assertThat(twoVariants).contains("confidence: medium");
        assertThat(threeVariants).contains("confidence: high");
    }

    @Test
    void doesNotReportAConstantThatNeverChanges() {
        DatabaseAdvisorRuleResultDto result = evaluate(List.of(
                statement("select * from orders where deleted = 0"),
                statement("select * from orders where deleted = 0"),
                statement("select * from orders where deleted = 0")));

        assertThat(result.status()).isEqualTo(DatabaseAdvisorRuleSupport.PASS);
    }

    @Test
    void doesNotReportAFixedFrameworkDiscriminatorSuchAsHibernateSingleTableInheritance() {
        DatabaseAdvisorRuleResultDto result = evaluate(List.of(
                statement("select p.id, p.name from person p where p.dtype = 'EMPLOYEE'"),
                statement("select p.id, p.name from person p where p.dtype = 'EMPLOYEE'"),
                statement("select p.id, p.name from person p where p.dtype = 'EMPLOYEE'")));

        assertThat(result.status()).isEqualTo(DatabaseAdvisorRuleSupport.PASS);
    }

    @Test
    void reportsAFrameworkGeneratedDiscriminatorThatVariesAcrossSubtypes() {
        DatabaseAdvisorRuleResultDto result = evaluate(List.of(
                statement("select p.id from person p where p.dtype = 'EMPLOYEE'"),
                statement("select p.id from person p where p.dtype = 'MANAGER'"),
                statement("select p.id from person p where p.dtype = 'CONTRACTOR'")));

        assertThat(result.status()).isEqualTo(DatabaseAdvisorRuleSupport.VIOLATION);
        assertThat(result.sampleViolations().toString())
                .doesNotContain("EMPLOYEE")
                .doesNotContain("MANAGER");
    }

    @Test
    void doesNotReportProperlyParameterizedStatements() {
        DatabaseAdvisorRuleResultDto result = evaluate(List.of(
                statement("select * from orders where customer_id = ?"),
                statement("select * from orders where customer_id = ?"),
                statement("select * from orders where customer_id = ?")));

        assertThat(result.status()).isEqualTo(DatabaseAdvisorRuleSupport.PASS);
    }

    @Test
    void doesNotReportALiteralOutsideAFilteringPosition() {
        DatabaseAdvisorRuleResultDto result = evaluate(List.of(
                statement("insert into audit (id, note) values (1, 'a')"),
                statement("insert into audit (id, note) values (2, 'b')")));

        assertThat(result.status()).isEqualTo(DatabaseAdvisorRuleSupport.PASS);
    }

    @Test
    void doesNotReportOneOffStatementsThatNeverRepeat() {
        DatabaseAdvisorRuleResultDto result = evaluate(List.of(
                statement("select * from orders where customer_id = 17"),
                statement("select * from products where sku = 'X'")));

        assertThat(result.status()).isEqualTo(DatabaseAdvisorRuleSupport.PASS);
    }

    @Test
    void singlesOutExecutionsThatUsedAPlainStatement() {
        DatabaseAdvisorRuleResultDto result = evaluate(List.of(
                statement("select * from orders where customer_id = 17", "STATEMENT"),
                statement("select * from orders where customer_id = 42", "STATEMENT")));

        assertThat(result.sampleViolations().get(0)).contains("2 via a plain Statement");
    }

    @Test
    void ranksTheShapeWithTheMostVariantsFirst() {
        List<SqlTraceEntryDto> statements = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            statements.add(statement("select * from a where id = " + i));
        }
        for (int i = 0; i < 5; i++) {
            statements.add(statement("select * from b where id = " + i));
        }

        DatabaseAdvisorRuleResultDto result = evaluate(statements);

        assertThat(result.sampleViolations()).hasSize(2);
        assertThat(result.sampleViolations().get(0)).contains("from b");
        assertThat(result.sampleViolations().get(1)).contains("from a");
    }

    @Test
    void boundsTheDistinctTextsItCountsAndSaysSo() {
        List<SqlTraceEntryDto> statements = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            statements.add(statement("select * from a where id = " + i));
        }

        DatabaseAdvisorRuleResultDto result = evaluate(statements);

        assertThat(result.sampleViolations().get(0)).contains("300 executions");
        assertThat(result.sampleViolations().get(0)).contains("64+ distinct texts");
    }

    @Test
    void boundsTheNumberOfShapesItTracks() {
        List<SqlTraceEntryDto> statements = new ArrayList<>();
        for (int table = 0; table < 800; table++) {
            statements.add(statement("select * from t" + table + " where id = 1"));
            statements.add(statement("select * from t" + table + " where id = 2"));
        }

        DatabaseAdvisorRuleResultDto result = evaluate(statements);

        assertThat(result.status()).isEqualTo(DatabaseAdvisorRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(500);
        assertThat(result.sampleViolations()).hasSize(10);
    }

    @Test
    void truncatesAVeryLongShapeInsteadOfPrintingIt() {
        String longSql = "select " + "a, ".repeat(200) + "b from orders where customer_id = ";
        DatabaseAdvisorRuleResultDto result = evaluate(List.of(statement(longSql + "1"), statement(longSql + "2")));

        assertThat(result.sampleViolations().get(0)).contains("…");
        assertThat(result.sampleViolations().get(0)).endsWith("confidence: medium).");
        assertThat(result.sampleViolations().get(0).length()).isLessThanOrEqualTo(DetailText.DEFAULT_MAX_CHARS);
    }

    @Test
    void ignoresBlankStatementTextInsteadOfFailing() {
        DatabaseAdvisorRuleResultDto result = evaluate(List.of(statement("  "), statement(null)));

        assertThat(result.status()).isEqualTo(DatabaseAdvisorRuleSupport.PASS);
    }
}
