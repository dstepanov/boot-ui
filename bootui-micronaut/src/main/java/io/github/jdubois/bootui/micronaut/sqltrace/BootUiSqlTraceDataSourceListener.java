package io.github.jdubois.bootui.micronaut.sqltrace;

import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTracingProxies;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import jakarta.inject.Singleton;
import javax.sql.DataSource;

/**
 * Wraps every {@code DataSource} bean in the SQL-tracing proxy as it is created, which is what fills the
 * SQL Trace panel and feeds the Database advisor's runtime-SQL rules.
 *
 * <p>The Micronaut analogue of the Quarkus adapter's alternative traced-datasource producer. Micronaut's
 * {@link BeanCreatedEventListener} is the idiomatic seam for this — it is the same hook
 * {@code micronaut-liquibase} uses to run migrations against a datasource — and it means tracing applies to
 * <em>every</em> datasource the application defines, named or not, without the adapter having to know how
 * they are configured.
 *
 * <p>Wrapping is skipped entirely when tracing is disabled ({@code bootui.sql-trace.enabled=false}), in
 * which case the application's own datasource is returned untouched and there is no proxy in the data path
 * at all. It also runs at lowest precedence so any wrapping the application or another library performs is
 * already in place, and BootUI observes what actually executes.
 */
@RequiresBootUi
@Singleton
@Order(Ordered.LOWEST_PRECEDENCE)
public class BootUiSqlTraceDataSourceListener implements BeanCreatedEventListener<DataSource> {

    private final SqlTraceRecorder recorder;

    public BootUiSqlTraceDataSourceListener(SqlTraceRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public DataSource onCreated(BeanCreatedEvent<DataSource> event) {
        DataSource dataSource = event.getBean();
        if (!recorder.isEnabled() || dataSource == null) {
            return dataSource;
        }
        try {
            recorder.registerDataSource(name(event));
            return SqlTracingProxies.wrap(dataSource, recorder);
        } catch (RuntimeException ex) {
            // A datasource that cannot be proxied is returned as-is: diagnostics must never cost the
            // application its database access.
            return dataSource;
        }
    }

    /** The datasource's configured name, which is what the panel groups statements by. */
    private static String name(BeanCreatedEvent<DataSource> event) {
        var identifier = event.getBeanIdentifier();
        return identifier == null || identifier.getName() == null ? "default" : identifier.getName();
    }
}
