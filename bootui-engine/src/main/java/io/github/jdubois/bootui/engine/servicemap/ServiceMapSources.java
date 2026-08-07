package io.github.jdubois.bootui.engine.servicemap;

import io.github.jdubois.bootui.core.dto.HikariPoolDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import java.util.List;

/**
 * The already-gathered, already-masked evidence a single Live Flow service map is assembled from.
 *
 * <p>Each source carries an explicit {@code *Available} flag alongside its data so the assembler never has
 * to guess why a list is empty. "Available" means the owning panel is both enabled and usable on this
 * runtime <em>and</em> its capture is switched on. A disabled panel therefore contributes nothing and is
 * reported as absent, rather than silently looking like a dependency with no traffic.</p>
 *
 * <p>Adapters fill this record from beans they already own; they never reshape, re-filter, or re-mask the
 * evidence. That keeps the interpretation rules in one framework-neutral place and keeps Spring MVC, Spring
 * WebFlux, and Quarkus byte-identical.</p>
 *
 * @param inboundAvailable whether the HTTP Exchanges panel contributed
 * @param inboundExchanges completed incoming requests, already self-filtered and masked
 * @param restClientAvailable whether the REST Client panel contributed
 * @param restClientCalls completed outbound HTTP client calls, already masked
 * @param jdbcPoolsAvailable whether the Connection Pools panel contributed
 * @param jdbcPools configured connection pools, with URLs and usernames already masked
 * @param sqlAvailable whether the SQL Trace panel contributed
 * @param sqlStatements completed JDBC statement executions
 * @param sqlDataSourceNames names of the datasources SQL tracing actually wrapped, used to decide whether
 *     statement evidence may be attributed to a single pool
 * @param kafkaAvailable whether the Kafka panel contributed
 * @param kafkaMessages captured Kafka messages; only producer records become outbound dependencies
 * @param rabbitAvailable whether the RabbitMQ panel contributed
 * @param rabbitMessages captured AMQP messages; only publisher records become outbound dependencies
 */
public record ServiceMapSources(
        boolean inboundAvailable,
        List<HttpExchangeDto> inboundExchanges,
        boolean restClientAvailable,
        List<RestClientTraceEntryDto> restClientCalls,
        boolean jdbcPoolsAvailable,
        List<HikariPoolDto> jdbcPools,
        boolean sqlAvailable,
        List<SqlTraceEntryDto> sqlStatements,
        List<String> sqlDataSourceNames,
        boolean kafkaAvailable,
        List<KafkaActivityRecorder.CapturedMessage> kafkaMessages,
        boolean rabbitAvailable,
        List<RabbitActivityRecorder.CapturedMessage> rabbitMessages) {

    public ServiceMapSources {
        inboundExchanges = inboundExchanges == null ? List.of() : List.copyOf(inboundExchanges);
        restClientCalls = restClientCalls == null ? List.of() : List.copyOf(restClientCalls);
        jdbcPools = jdbcPools == null ? List.of() : List.copyOf(jdbcPools);
        sqlStatements = sqlStatements == null ? List.of() : List.copyOf(sqlStatements);
        sqlDataSourceNames = sqlDataSourceNames == null ? List.of() : List.copyOf(sqlDataSourceNames);
        kafkaMessages = kafkaMessages == null ? List.of() : List.copyOf(kafkaMessages);
        rabbitMessages = rabbitMessages == null ? List.of() : List.copyOf(rabbitMessages);
    }

    /** An entirely absent evidence set, used when every source panel is disabled or unavailable. */
    public static ServiceMapSources empty() {
        return new ServiceMapSources(
                false, List.of(), false, List.of(), false, List.of(), false, List.of(), List.of(), false, List.of(),
                false, List.of());
    }
}
