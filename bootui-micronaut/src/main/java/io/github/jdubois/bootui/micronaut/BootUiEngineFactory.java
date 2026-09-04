package io.github.jdubois.bootui.micronaut;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.engine.activity.ActivityInstanceIds;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivityStoreFactory;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.architecture.ArchitecturePlatform;
import io.github.jdubois.bootui.engine.architecture.ArchitectureScanner;
import io.github.jdubois.bootui.engine.beans.BeansService;
import io.github.jdubois.bootui.engine.cache.CacheService;
import io.github.jdubois.bootui.engine.config.ConfigService;
import io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorScanner;
import io.github.jdubois.bootui.engine.datasource.ConnectionPoolService;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import io.github.jdubois.bootui.engine.email.EmailStore;
import io.github.jdubois.bootui.engine.errorcontract.ErrorContractService;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionsService;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceService;
import io.github.jdubois.bootui.engine.flyway.FlywayService;
import io.github.jdubois.bootui.engine.github.DefaultGitHubTokenProvider;
import io.github.jdubois.bootui.engine.github.GitHubDashboardConfig;
import io.github.jdubois.bootui.engine.github.GitHubDashboardService;
import io.github.jdubois.bootui.engine.health.HealthService;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpService;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpSettings;
import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import io.github.jdubois.bootui.engine.hibernate.HibernateStatisticsService;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.liquibase.LiquibaseService;
import io.github.jdubois.bootui.engine.loggers.LoggersService;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import io.github.jdubois.bootui.engine.mappings.MappingsService;
import io.github.jdubois.bootui.engine.memory.MemoryReportProvider;
import io.github.jdubois.bootui.engine.memory.MemoryScanner;
import io.github.jdubois.bootui.engine.metrics.MeterSelfFilter;
import io.github.jdubois.bootui.engine.metrics.MetricsReportProvider;
import io.github.jdubois.bootui.engine.pentesting.PentestingScanner;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.github.jdubois.bootui.engine.restapi.RestApiScanner;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.safety.ApiTokenAuthenticator;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTasksService;
import io.github.jdubois.bootui.engine.security.SecurityEventBuffer;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.github.jdubois.bootui.engine.telemetry.AiUsageService;
import io.github.jdubois.bootui.engine.telemetry.AiUsageSettings;
import io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import io.github.jdubois.bootui.engine.telemetry.TracesService;
import io.github.jdubois.bootui.engine.threads.ThreadDumpService;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.engine.web.HttpProbeService;
import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder;
import io.github.jdubois.bootui.engine.websocket.WebSocketService;
import io.github.jdubois.bootui.engine.websocket.WebSocketSettings;
import io.github.jdubois.bootui.micronaut.beans.MicronautBeanProvider;
import io.github.jdubois.bootui.micronaut.cache.MicronautCacheProvider;
import io.github.jdubois.bootui.micronaut.config.MicronautConfigProvider;
import io.github.jdubois.bootui.micronaut.datasource.MicronautDataSourceProvider;
import io.github.jdubois.bootui.micronaut.datasource.MicronautHikariConnectionPoolProvider;
import io.github.jdubois.bootui.micronaut.errorcontract.MicronautErrorContractProvider;
import io.github.jdubois.bootui.micronaut.faulttolerance.MicronautRetryPolicyProvider;
import io.github.jdubois.bootui.micronaut.flyway.MicronautFlywayProvider;
import io.github.jdubois.bootui.micronaut.github.GitHubApiClient;
import io.github.jdubois.bootui.micronaut.github.MicronautGitHubSettings;
import io.github.jdubois.bootui.micronaut.health.MicronautHealthGuidance;
import io.github.jdubois.bootui.micronaut.hibernate.MicronautEntityDiscovery;
import io.github.jdubois.bootui.micronaut.hibernate.MicronautHibernatePropertyLookup;
import io.github.jdubois.bootui.micronaut.hibernate.MicronautHibernateStatisticsProvider;
import io.github.jdubois.bootui.micronaut.liquibase.MicronautLiquibaseProvider;
import io.github.jdubois.bootui.micronaut.logging.MicronautLoggerProvider;
import io.github.jdubois.bootui.micronaut.mappings.MicronautMappingProvider;
import io.github.jdubois.bootui.micronaut.pentesting.MicronautPentestingObservationCollector;
import io.github.jdubois.bootui.micronaut.scheduled.MicronautScheduledTaskProvider;
import io.github.jdubois.bootui.micronaut.telemetry.MicronautTelemetrySettings;
import io.github.jdubois.bootui.micronaut.vulnerabilities.MicronautDependencyProvider;
import io.github.jdubois.bootui.micronaut.vulnerabilities.MicronautVulnerabilitySettings;
import io.github.jdubois.bootui.micronaut.vulnerabilities.OsvVulnerabilityScanner;
import io.github.jdubois.bootui.micronaut.websocket.MicronautWebSocketConnectionCapture;
import io.github.jdubois.bootui.micronaut.websocket.MicronautWebSocketMetadataProvider;
import io.github.jdubois.bootui.micronaut.websocket.MicronautWebSocketSessionProvider;
import io.github.jdubois.bootui.spi.FaultTolerancePolicyProvider;
import io.github.jdubois.bootui.spi.HealthProvider;
import io.github.jdubois.bootui.spi.LoggerProvider;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.env.Environment;
import io.micronaut.web.router.Router;
import jakarta.inject.Singleton;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Builds the framework-neutral {@code bootui-engine} services as Micronaut singletons.
 *
 * <p>This is the Micronaut analogue of the Spring adapter's {@code BootUiEngineConfiguration} and of the
 * Quarkus adapter's {@code BootUiEngineProducer}: the engine services are annotation-free by design, so
 * each adapter builds them from its own inputs. Two shapes recur, and both are deliberate.
 *
 * <ul>
 *   <li>A service that needs a <em>live</em> policy is given the <em>concrete</em> adapter bean — the
 *       {@link MicronautExposurePolicy} rather than the {@code ExposurePolicy} interface, the
 *       {@link MicronautMemoryRuntimeConfig} rather than {@code MemoryRuntimeConfig} — so adding another
 *       implementation of the same SPI later can never make this wiring ambiguous. The policy is then read
 *       per call, so a configuration change takes effect on the next request with no restart.</li>
 *   <li>A service that needs only <em>static</em> settings receives an immutable settings record mapped
 *       inline from configuration, matching the other adapters' defaults key for key.</li>
 * </ul>
 *
 * <p>Every bean here carries {@link RequiresBootUi}, so when the console is not active none of them is
 * created at all.
 */
@RequiresBootUi
@Factory
public class BootUiEngineFactory {

    /**
     * BootUI's own packages, used both to hide the console's internals from the panels that inventory the
     * application (Loggers, Beans, Mappings) and to keep its own loggers writable regardless of that
     * preference. Scoped to the adapter and the shared core rather than the whole
     * {@code io.github.jdubois.bootui} tree, so an application that happens to live under that root package
     * is not swallowed, and neither are the framework-neutral {@code engine}/{@code spi} packages.
     */
    private static final InternalPackageMatcher INTERNAL_PACKAGES =
            new InternalPackageMatcher(List.of("io.github.jdubois.bootui.micronaut", "io.github.jdubois.bootui.core"));

    /**
     * Swagger's {@code @Operation} annotation, brought onto the classpath by Micronaut OpenAPI. Its presence
     * is what the REST API advisor's documentation rules evaluate against, exactly as on the other adapters.
     */
    private static final String OPENAPI_OPERATION_ANNOTATION = "io.swagger.v3.oas.annotations.Operation";

    private static final boolean OPENAPI_PRESENT = isPresent(OPENAPI_OPERATION_ANNOTATION);

    /** HikariCP, the pool {@code micronaut-jdbc-hikari} configures; the Connection Pools panel needs it. */
    private static final boolean HIKARI_PRESENT = isPresent("com.zaxxer.hikari.HikariDataSource");

    /** Micronaut's cache abstraction; the Cache panel needs it. */
    private static final boolean CACHE_PRESENT = isPresent("io.micronaut.cache.CacheManager");

    /** micronaut-flyway's per-datasource configuration; the Flyway panel needs it. */
    private static final boolean FLYWAY_PRESENT = isPresent("io.micronaut.flyway.FlywayConfigurationProperties");

    /** micronaut-retry; the Fault Tolerance panel needs it. */
    private static final boolean RETRY_PRESENT = isPresent("io.micronaut.retry.annotation.Retryable");

    /** micronaut-websocket; the WebSockets panel needs it. */
    private static final boolean WEBSOCKET_PRESENT = isPresent("io.micronaut.websocket.WebSocketSession");

    /** Hibernate ORM; the Hibernate advisor and the Hibernate Statistics panel need it. */
    private static final boolean HIBERNATE_PRESENT = isPresent("org.hibernate.SessionFactory");

    /** micronaut-liquibase's per-datasource configuration; the Liquibase panel needs it. */
    private static final boolean LIQUIBASE_PRESENT =
            isPresent("io.micronaut.liquibase.LiquibaseConfigurationProperties");

    /**
     * Whether an optional integration is on the application's classpath. Every optional panel is gated on one
     * of these probes so a class it would reference is never linked in an application that does not have it.
     */
    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, BootUiEngineFactory.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    /**
     * The bearer-token authenticator guarding non-loopback API access. The token is taken from
     * {@code bootui.authentication.token} when configured, and generated at startup otherwise —
     * {@link BootUiMicronautStartupBanner} logs a generated one once, and only when remote access is
     * actually configured.
     */
    @Singleton
    ApiTokenAuthenticator apiTokenAuthenticator(Environment environment) {
        return new ApiTokenAuthenticator(environment
                .getProperty("bootui.authentication.token", String.class)
                .orElse(null));
    }

    /**
     * The HTTP exchange ring buffer fed by {@link io.github.jdubois.bootui.micronaut.web.MicronautHttpExchangeCaptureFilter}.
     * Micronaut has no Actuator {@code HttpExchangeRepository}, so this is the capture source for the HTTP
     * Exchanges panel. Capacity bounds memory ({@code bootui.http-exchanges.max-exchanges}, default 200,
     * unified with the Spring and Quarkus adapters); the buffer caps and reverses, the engine service masks.
     */
    @Singleton
    HttpExchangeBuffer httpExchangeBuffer(Environment environment) {
        int maxExchanges = environment
                .getProperty("bootui.http-exchanges.max-exchanges", Integer.class)
                .orElse(200);
        return new HttpExchangeBuffer(maxExchanges);
    }

    /**
     * The Log Tail ring buffer fed by the Logback appender {@code BootUiLogbackCapture} installs. Capped to
     * {@code 500} lines and an approximate byte budget ({@code bootui.log-tail.max-bytes}, default
     * unbounded) so a long-lived development process stays bounded, matching the other adapters.
     */
    @Singleton
    LogTailBuffer logTailBuffer(Environment environment) {
        long maxBytes =
                environment.getProperty("bootui.log-tail.max-bytes", Long.class).orElse(0L);
        return new LogTailBuffer(LogTailBuffer.DEFAULT_MAX_LINES, maxBytes);
    }

    /**
     * The Exceptions store fed by the Logback appender {@code BootUiLogbackCapture} installs. Application
     * packages are stamped in so the engine can mark the application frames in a captured stack; they come
     * from the same live provider the advisors use.
     */
    @Singleton
    ExceptionStore exceptionStore(Environment environment, MicronautBasePackageProvider basePackages) {
        ExceptionStore store = new ExceptionStore(
                environment
                        .getProperty("bootui.exceptions.max-groups", Integer.class)
                        .orElse(100),
                environment
                        .getProperty("bootui.exceptions.max-occurrences-per-group", Integer.class)
                        .orElse(25),
                environment
                        .getProperty("bootui.exceptions.max-stack-frames", Integer.class)
                        .orElse(50));
        store.setApplicationPackages(basePackages.basePackages());
        return store;
    }

    /** Display masking and DTO assembly for the Exceptions panel, shared with every adapter. */
    @Singleton
    ExceptionsService exceptionsService(MicronautExposurePolicy exposure) {
        return new ExceptionsService(exposure);
    }

    /**
     * The security-event ring buffer fed by {@code MicronautSecurityEventCapture}. Micronaut has no Actuator
     * {@code AuditEventRepository}, so this is the capture source for the Security Logs panel. Always
     * produced, so the panel's read path works whether or not micronaut-security is present. Capacity bounds
     * memory ({@code bootui.security-logs.max-logs}, default 500, matching the other adapters).
     */
    @Singleton
    SecurityEventBuffer securityEventBuffer(Environment environment) {
        int capacity = environment
                .getProperty("bootui.security-logs.max-logs", Integer.class)
                .orElse(500);
        return new SecurityEventBuffer(capacity);
    }

    @Singleton
    ThreadDumpService threadDumpService(MicronautExposurePolicy exposure) {
        return new ThreadDumpService(exposure);
    }

    @Singleton
    MemoryReportProvider memoryReportProvider(MicronautMemoryRuntimeConfig runtimeConfig) {
        return new MemoryReportProvider(runtimeConfig);
    }

    /**
     * The Memory advisor scanner over the shared {@link ThreadDumpService}. Always available — the scanner
     * reads only JMX management beans (heap/GC/threads/class-loading) present on every JVM, so no capability
     * gate or optional dependency is involved. A singleton matches the scanner's cross-scan GC-trend state.
     */
    @Singleton
    MemoryScanner memoryScanner(ThreadDumpService threadDumpService) {
        return MemoryScanner.create(threadDumpService, Clock.systemUTC());
    }

    /**
     * The HTTP Probe service over {@link MicronautServerPortSupplier}, which resolves the port the embedded
     * server is actually bound to. The engine probes the application's own loopback port, read live on
     * every probe.
     */
    @Singleton
    HttpProbeService httpProbeService(MicronautServerPortSupplier serverPort) {
        return new HttpProbeService(serverPort);
    }

    @Singleton
    HeapDumpService heapDumpService(Environment environment) {
        HeapDumpSettings settings = new HeapDumpSettings(
                environment
                        .getProperty("bootui.heap-dump.output-dir", String.class)
                        .orElse(".bootui/heap-dumps"),
                environment
                        .getProperty("bootui.heap-dump.capture-enabled", Boolean.class)
                        .orElse(Boolean.TRUE),
                environment
                        .getProperty("bootui.heap-dump.allow-raw-download", Boolean.class)
                        .orElse(Boolean.FALSE),
                environment
                        .getProperty("bootui.heap-dump.max-dumps", Integer.class)
                        .orElse(5),
                environment
                        .getProperty("bootui.heap-dump.max-classes", Integer.class)
                        .orElse(1000),
                environment
                        .getProperty("bootui.heap-dump.top-classes", Integer.class)
                        .orElse(25));
        return new HeapDumpService(settings);
    }

    /**
     * The Metrics service over Micrometer. Micrometer is a sanctioned {@code bootui-engine} dependency, so
     * its API is always on the classpath, but a {@link MeterRegistry} <em>bean</em> exists only when the
     * application adds {@code micronaut-micrometer}. The registry is therefore resolved live per request
     * through the bean context: absent &rarr; {@code null} &rarr; the engine renders the panel as
     * unavailable; present &rarr; the live registry is read on every report. The meter-visibility predicate
     * is the shared engine {@link MeterSelfFilter}, so the panel never reports BootUI's own
     * {@code /bootui/**} traffic.
     */
    @Singleton
    MetricsReportProvider metricsReportProvider(BeanContext beanContext, SelfTelemetryClassifier selfClassifier) {
        MeterSelfFilter meterFilter = new MeterSelfFilter(selfClassifier);
        return new MetricsReportProvider(() -> resolveRegistry(beanContext), meterFilter::shouldIncludeMeter);
    }

    /**
     * Resolves the application's meter registry, or {@code null} when it has none. When several registries
     * are present (for instance several Micrometer backends) the first is used, like the Spring and Quarkus
     * adapters.
     */
    static MeterRegistry resolveRegistry(BeanContext beanContext) {
        try {
            return beanContext.findBean(MeterRegistry.class).orElse(null);
        } catch (RuntimeException ex) {
            return beanContext.getBeansOfType(MeterRegistry.class).stream()
                    .findFirst()
                    .orElse(null);
        }
    }

    /** The bounds the in-process span capture and the Traces/AI read models run under. */
    @Singleton
    MicronautTelemetrySettings telemetrySettings(Environment environment) {
        return new MicronautTelemetrySettings(environment);
    }

    /** The bounded in-memory span store behind the Traces and AI Framework panels. */
    @Singleton
    TelemetryStore telemetryStore(MicronautTelemetrySettings settings) {
        return new TelemetryStore(settings);
    }

    @Singleton
    TracesService tracesService(
            TelemetryStore store, MicronautTelemetrySettings settings, SelfTelemetryClassifier selfClassifier) {
        return new TracesService(store, settings, selfClassifier);
    }

    /**
     * The AI Framework read model over the same captured spans, which it interprets through the
     * OpenTelemetry GenAI semantic conventions.
     */
    @Singleton
    AiUsageService aiUsageService(TelemetryStore store, MicronautTelemetrySettings settings, Environment environment) {
        Supplier<AiUsageSettings> aiSettings = () -> new AiUsageSettings(
                settings.enabled(),
                environment
                        .getProperty("bootui.ai.max-recent-chats", Integer.class)
                        .orElse(100),
                environment
                        .getProperty("bootui.ai.token-series-minutes", Integer.class)
                        .orElse(60),
                environment
                        .getProperty("bootui.ai.show-content-capture-banner", Boolean.class)
                        .orElse(Boolean.TRUE));
        return new AiUsageService(store, aiSettings, System::currentTimeMillis);
    }

    /**
     * The classifier that recognizes BootUI's own telemetry, shared by the Metrics panel's meter filter and
     * every capture point, built from {@code bootui.monitoring.exclude-self} plus the configured console
     * mounts so a custom {@code bootui.path} is still recognized as self-traffic.
     */
    @Singleton
    SelfTelemetryClassifier selfTelemetryClassifier(Environment environment) {
        boolean excludeSelf = environment
                .getProperty("bootui.monitoring.exclude-self", Boolean.class)
                .orElse(Boolean.TRUE);
        return new SelfTelemetryClassifier(
                excludeSelf,
                MicronautBootUiPaths.safeUiPath(environment),
                MicronautBootUiPaths.safeApiPath(environment));
    }

    /**
     * The Loggers service over the Logback-backed {@link MicronautLoggerProvider}. Mirrors the Spring and
     * Quarkus factories' two-predicate split: a read-visibility predicate that honors
     * {@code bootui.monitoring.exclude-self} (default {@code true}, hiding BootUI's own loggers from the
     * panel) and an independent write guard pinned to BootUI-owned loggers regardless of that preference,
     * so a read toggle can never fail the write open.
     */
    @Singleton
    LoggersService loggersService(Environment environment) {
        boolean excludeSelf = environment
                .getProperty("bootui.monitoring.exclude-self", Boolean.class)
                .orElse(Boolean.TRUE);
        LoggerProvider provider = new MicronautLoggerProvider();
        Predicate<String> readVisible = name -> !excludeSelf || !INTERNAL_PACKAGES.matchesName(name);
        Predicate<String> writeBlocked = INTERNAL_PACKAGES::matchesName;
        return new LoggersService(provider, readVisible, writeBlocked);
    }

    /**
     * The Beans panel over the Micronaut bean container. The provider enumerates definitions live on every
     * request; the engine {@link BeansService} only sorts, classification/free-text filters and pages —
     * exactly as the Spring adapter builds its service over the Actuator-backed provider.
     */
    @Singleton
    MicronautBeanProvider micronautBeanProvider(BeanContext beanContext) {
        return new MicronautBeanProvider(beanContext);
    }

    @Singleton
    BeansService beansService(MicronautBeanProvider beanProvider) {
        return new BeansService(beanProvider);
    }

    /**
     * The Mappings panel over the live {@link Router}. The engine {@link MappingsService} owns only the
     * framework-neutral sort, free-text query and paging; the route enumeration and BootUI self-data
     * filtering live in {@link MicronautMappingProvider}.
     */
    @Singleton
    MicronautMappingProvider micronautMappingProvider(Router router) {
        return new MicronautMappingProvider(router);
    }

    @Singleton
    MappingsService mappingsService(MicronautMappingProvider mappingProvider) {
        return new MappingsService(mappingProvider);
    }

    @Singleton
    MicronautConfigProvider micronautConfigProvider(Environment environment) {
        return new MicronautConfigProvider(environment);
    }

    @Singleton
    ConfigService configService(MicronautConfigProvider provider, MicronautExposurePolicy exposure) {
        return new ConfigService(provider, exposure);
    }

    /**
     * The Health panel service. The {@link HealthProvider} exists only when {@code micronaut-management} is
     * on the application's classpath (its own bean condition), so it is resolved through the bean context:
     * absent &rarr; {@code null} &rarr; the engine renders {@link MicronautHealthGuidance}'s honest setup
     * steps instead of an empty tree.
     */
    @Singleton
    HealthService healthService(BeanContext beanContext) {
        HealthProvider provider = beanContext.findBean(HealthProvider.class).orElse(null);
        return new HealthService(provider, MicronautHealthGuidance.INSTANCE);
    }

    /**
     * The Architecture (ArchUnit) hygiene scanner. The base packages are read <em>live</em> on every scan
     * through the supplier (never snapshotted at construction), exactly as the Spring adapter binds its
     * scanner over {@code AutoConfigurationPackages}; the ArchUnit import itself runs only on the explicit
     * {@code POST /scan} action, never at construction.
     */
    @Singleton
    ArchitectureScanner architectureScanner(MicronautBasePackageProvider basePackages) {
        return ArchitectureScanner.usingClasspath(
                basePackages::basePackages, ArchitecturePlatform.MICRONAUT, Clock.systemUTC());
    }

    /**
     * The REST API advisor over the application's controllers. Mirrors the Architecture scanner: the shared
     * engine {@link RestApiScanner} imports the host classes with ArchUnit bounded to the live base packages
     * and runs the curated ruleset, only on the explicit scan action. The OpenAPI documentation rules probe
     * for Micronaut OpenAPI's {@code @Operation} annotation on the classpath, the same way the Spring adapter
     * probes for Swagger's.
     */
    @Singleton
    RestApiScanner restApiScanner(MicronautBasePackageProvider basePackages) {
        return RestApiScanner.usingClasspath(basePackages::basePackages, () -> OPENAPI_PRESENT, Clock.systemUTC());
    }

    @Singleton
    MicronautErrorContractProvider micronautErrorContractProvider(BeanContext beanContext) {
        return new MicronautErrorContractProvider(beanContext);
    }

    /**
     * The REST API panel's error-contract catalogue. The engine owns classification, precedence resolution,
     * ordering, bounding, query and paging; the Micronaut-specific discovery of {@code @Error} methods and
     * {@code ExceptionHandler} beans lives in the provider.
     */
    @Singleton
    ErrorContractService errorContractService(MicronautErrorContractProvider errorContractProvider) {
        return new ErrorContractService(errorContractProvider);
    }

    /**
     * The Pentesting (local OWASP hygiene) scanner. The engine owns the probe methodology — synthetic
     * loopback URI assembly plus GET/OPTIONS probes — and fires it only on demand, never at construction;
     * this adapter supplies the live observation.
     */
    @Singleton
    PentestingScanner pentestingScanner(Environment environment, MicronautServerPortSupplier serverPort) {
        MicronautPentestingObservationCollector collector =
                new MicronautPentestingObservationCollector(environment, serverPort);
        return PentestingScanner.usingObservation(collector::collect, Clock.systemUTC());
    }

    /**
     * The Scheduled Tasks panel. Always wired: Micronaut's scheduler is part of {@code micronaut-context}, so
     * the capability is always present and an empty task list is an honest answer rather than an unavailable
     * panel.
     */
    @Singleton
    ScheduledTasksService scheduledTasksService(BeanContext beanContext) {
        return new ScheduledTasksService(new MicronautScheduledTaskProvider(beanContext));
    }

    /**
     * The Database Connection Pools panel. The provider is built unconditionally but reports nothing when the
     * application has no datasource, so the engine renders the panel's own empty state; when HikariCP is
     * absent from the classpath entirely the provider is not created at all and the service degrades to a
     * {@code null} provider, exactly as the other adapters do.
     */
    /**
     * The application's datasources, named as configured. Shared by the Database advisor and the Live
     * Activity persistence switch so both see the same set under the same names.
     */
    @Singleton
    MicronautDataSourceProvider micronautDataSourceProvider(BeanContext beanContext) {
        return new MicronautDataSourceProvider(beanContext);
    }

    @Singleton
    ConnectionPoolService connectionPoolService(BeanContext beanContext, MicronautExposurePolicy exposure) {
        return new ConnectionPoolService(
                HIKARI_PRESENT ? new MicronautHikariConnectionPoolProvider(beanContext) : null, exposure);
    }

    /**
     * The Cache panel. Like the pools service, the provider exists only when the cache API is on the
     * classpath; without it the engine renders the panel's honest unavailable state.
     */
    @Singleton
    CacheService cacheService(BeanContext beanContext, SelfTelemetryClassifier selfClassifier) {
        MeterSelfFilter meterFilter = new MeterSelfFilter(selfClassifier);
        return new CacheService(
                CACHE_PRESENT ? new MicronautCacheProvider(beanContext) : null,
                () -> resolveRegistry(beanContext),
                meterFilter::shouldIncludeMeter);
    }

    /**
     * The Flyway panel. Wired only when Flyway's own API is on the classpath; without it the service is
     * built with a {@code null} provider and the engine renders the panel's honest unavailable state.
     */
    @Singleton
    FlywayService flywayService(BeanContext beanContext) {
        return new FlywayService(FLYWAY_PRESENT ? new MicronautFlywayProvider(beanContext) : null);
    }

    /** The Liquibase panel, gated on Liquibase's own API exactly like the Flyway panel above. */
    @Singleton
    LiquibaseService liquibaseService(BeanContext beanContext, Environment environment) {
        return new LiquibaseService(
                LIQUIBASE_PRESENT ? new MicronautLiquibaseProvider(beanContext, environment) : null);
    }

    /**
     * The SQL Trace recorder. Always produced so the panel's read path works, and filled only when
     * {@code BootUiSqlTraceDataSourceListener} has wrapped at least one datasource — which it skips entirely
     * when {@code bootui.sql-trace.enabled=false}, leaving no proxy in the data path.
     *
     * <p>Parameter capture is off by default: a bound parameter is application data, and the panel only
     * reveals captured values when the live exposure policy allows them.
     */
    @Singleton
    SqlTraceRecorder sqlTraceRecorder(Environment environment) {
        return new SqlTraceRecorder(
                environment
                        .getProperty("bootui.sql-trace.enabled", Boolean.class)
                        .orElse(true),
                environment
                        .getProperty("bootui.sql-trace.recording", Boolean.class)
                        .orElse(true),
                environment
                        .getProperty("bootui.sql-trace.capture-parameters", Boolean.class)
                        .orElse(false),
                environment
                        .getProperty("bootui.sql-trace.capture-call-site", Boolean.class)
                        .orElse(true),
                environment
                        .getProperty("bootui.sql-trace.max-entries", Integer.class)
                        .orElse(200),
                environment
                        .getProperty("bootui.sql-trace.slow-query-threshold-millis", Long.class)
                        .orElse(100L),
                environment
                        .getProperty("bootui.sql-trace.max-sql-length", Integer.class)
                        .orElse(2000),
                environment
                        .getProperty("bootui.sql-trace.max-parameter-length", Integer.class)
                        .orElse(200),
                environment
                        .getProperty("bootui.sql-trace.n-plus-one-threshold", Integer.class)
                        .orElse(5));
    }

    /**
     * The Hibernate (ORM mapping) advisor. Entity discovery is resolved live on every scan, so an
     * application that has no persistence unit reports that honestly instead of failing, and one that gains
     * one later is picked up without a restart. Configuration is read through
     * {@link MicronautHibernatePropertyLookup}, which maps the rules' Hibernate/Spring key spellings onto
     * Micronaut's {@code jpa.*} namespace.
     */
    @Singleton
    HibernateScanner hibernateScanner(BeanContext beanContext, Environment environment) {
        Supplier<EntityDiscovery> discovery = () -> HIBERNATE_PRESENT
                ? MicronautEntityDiscovery.discover(beanContext)
                : EntityDiscovery.empty("Hibernate ORM is not on the classpath of this Micronaut application.");
        return HibernateScanner.using(
                discovery,
                new MicronautHibernatePropertyLookup(environment),
                () -> List.copyOf(environment.getActiveNames()),
                Clock.systemUTC());
    }

    /**
     * The Hibernate Statistics panel. The provider exists only when Hibernate is on the classpath; without
     * it the engine renders the panel's honest unavailable state.
     */
    @Singleton
    HibernateStatisticsService hibernateStatisticsService(BeanContext beanContext) {
        return new HibernateStatisticsService(
                HIBERNATE_PRESENT ? new MicronautHibernateStatisticsProvider(beanContext) : null);
    }

    /**
     * The Database advisor. Its rules read the application's datasources plus, when SQL tracing has captured
     * any, the statements actually executed — the runtime-SQL rules skip rather than report a clean result
     * they have no evidence for.
     */
    @Singleton
    DatabaseAdvisorScanner databaseAdvisorScanner(
            BeanContext beanContext,
            SqlTraceRecorder sqlTraceRecorder,
            MicronautDataSourceProvider dataSourceProvider) {
        Supplier<EntityDiscovery> discovery = () -> HIBERNATE_PRESENT
                ? MicronautEntityDiscovery.discover(beanContext)
                : EntityDiscovery.empty("Hibernate ORM is not on the classpath of this Micronaut application.");
        Supplier<List<SqlTraceEntryDto>> observedStatements = () -> sqlTraceRecorder.entries(false);
        return DatabaseAdvisorScanner.using(
                dataSourceProvider::dataSources, discovery, observedStatements, Clock.systemUTC());
    }

    /** The application's dependency inventory, read from the coordinates the runtime classpath's jars carry. */
    @Singleton
    MicronautDependencyProvider micronautDependencyProvider() {
        return new MicronautDependencyProvider();
    }

    /**
     * The OSV.dev vulnerability scanner. Follows the static-settings template — the scan has no live
     * override path — and is never invoked except by the user-initiated scan action.
     */
    @Singleton
    OsvVulnerabilityScanner osvVulnerabilityScanner(Environment environment) {
        return new OsvVulnerabilityScanner(MicronautVulnerabilitySettings.from(environment));
    }

    /** The bounds the WebSockets panel's inventory and activity capture run under. */
    @Singleton
    WebSocketSettings webSocketSettings(Environment environment) {
        return new WebSocketSettings(
                environment
                        .getProperty("bootui.websockets.enabled", Boolean.class)
                        .orElse(true),
                environment
                        .getProperty("bootui.websockets.capturing", Boolean.class)
                        .orElse(true),
                environment
                        .getProperty("bootui.websockets.max-endpoints", Integer.class)
                        .orElse(200),
                environment
                        .getProperty("bootui.websockets.max-sessions", Integer.class)
                        .orElse(200),
                environment
                        .getProperty("bootui.websockets.max-subscriptions", Integer.class)
                        .orElse(500),
                environment
                        .getProperty("bootui.websockets.max-activity-entries", Integer.class)
                        .orElse(500),
                environment
                        .getProperty("bootui.websockets.max-tracked-sessions", Integer.class)
                        .orElse(2_000));
    }

    @Singleton
    WebSocketActivityRecorder webSocketActivityRecorder(WebSocketSettings settings) {
        return new WebSocketActivityRecorder(settings);
    }

    /**
     * The WebSockets panel service. Both providers exist only when micronaut-websocket is on the classpath;
     * without it the engine renders the panel's honest unavailable state rather than an empty topology.
     */
    @Singleton
    WebSocketService webSocketService(
            BeanContext beanContext,
            WebSocketActivityRecorder recorder,
            WebSocketSettings settings,
            MicronautExposurePolicy exposure) {
        if (!WEBSOCKET_PRESENT) {
            return new WebSocketService(null, null, recorder, settings, exposure);
        }
        MicronautWebSocketConnectionCapture capture =
                beanContext.findBean(MicronautWebSocketConnectionCapture.class).orElse(null);
        return new WebSocketService(
                new MicronautWebSocketMetadataProvider(beanContext),
                capture == null ? null : new MicronautWebSocketSessionProvider(capture),
                recorder,
                settings,
                exposure);
    }

    /**
     * The Fault Tolerance event recorder, fed by {@code MicronautRetryEventCapture}. Always produced so the
     * panel's read path works; without micronaut-retry nothing ever records and the panel stays empty.
     */
    @Singleton
    FaultToleranceEventRecorder faultToleranceEventRecorder(Environment environment) {
        return new FaultToleranceEventRecorder(
                environment
                        .getProperty("bootui.fault-tolerance.enabled", Boolean.class)
                        .orElse(true),
                environment
                        .getProperty("bootui.fault-tolerance.max-events", Integer.class)
                        .orElse(200));
    }

    /**
     * The Fault Tolerance panel. The policy provider exists only when micronaut-retry is on the classpath;
     * with no provider the engine reports the panel unavailable rather than an empty policy list.
     */
    @Singleton
    FaultToleranceService faultToleranceService(
            BeanContext beanContext, FaultToleranceEventRecorder recorder, Environment environment) {
        List<FaultTolerancePolicyProvider> providers =
                RETRY_PRESENT ? List.of(new MicronautRetryPolicyProvider(beanContext)) : List.of();
        int maxEvents = environment
                .getProperty("bootui.fault-tolerance.max-events", Integer.class)
                .orElse(200);
        return new FaultToleranceService(providers, recorder, maxEvents);
    }

    /**
     * The REST Client trace recorder, fed by {@code MicronautRestClientTraceFilter}. Header capture is off
     * by default: an outbound request header can carry a credential, so the panel only shows headers when
     * they are explicitly enabled and the exposure policy allows values.
     */
    @Singleton
    RestClientTraceRecorder restClientTraceRecorder(Environment environment) {
        return new RestClientTraceRecorder(
                environment
                        .getProperty("bootui.rest-client-trace.enabled", Boolean.class)
                        .orElse(true),
                environment
                        .getProperty("bootui.rest-client-trace.recording", Boolean.class)
                        .orElse(true),
                environment
                        .getProperty("bootui.rest-client-trace.capture-headers", Boolean.class)
                        .orElse(false),
                environment
                        .getProperty("bootui.rest-client-trace.capture-call-site", Boolean.class)
                        .orElse(true),
                environment
                        .getProperty("bootui.rest-client-trace.max-entries", Integer.class)
                        .orElse(200),
                environment
                        .getProperty("bootui.rest-client-trace.slow-call-threshold-millis", Long.class)
                        .orElse(500L),
                environment
                        .getProperty("bootui.rest-client-trace.max-uri-length", Integer.class)
                        .orElse(2000),
                environment
                        .getProperty("bootui.rest-client-trace.max-header-value-length", Integer.class)
                        .orElse(200),
                environment
                        .getProperty("bootui.rest-client-trace.chatty-call-threshold", Integer.class)
                        .orElse(5));
    }

    /**
     * The Email Viewer capture service, filled by {@code BootUiEmailCaptureListener}. Always produced so the
     * panel's read path works even without Micronaut Email.
     *
     * <p>Unlike Spring's dev-trap, BootUI does not intercept the send on Micronaut — the message really goes
     * out — so {@code devTrap} is false and the panel reports these messages as sent, which is the honest
     * answer. Content is revealed by default ({@code bootui.email.mask-content}): an email body is not a
     * configuration secret, so reading it should not require flipping the global secret-exposure flag.
     */
    @Singleton
    EmailCaptureService emailCaptureService(
            MicronautExposurePolicy exposure, Environment environment, BeanContext beanContext) {
        int maxEntries = environment
                .getProperty("bootui.email.max-entries", Integer.class)
                .orElse(100);
        int maxBodyLength = environment
                .getProperty("bootui.email.max-body-length", Integer.class)
                .orElse(EmailStore.DEFAULT_MAX_BODY_LENGTH);
        boolean maskContent = environment
                .getProperty("bootui.email.mask-content", Boolean.class)
                .orElse(false);
        EmailCaptureService service =
                new EmailCaptureService(new EmailStore(maxEntries, maxBodyLength), exposure, false, maskContent);
        beanContext.findBean(TraceIdProvider.class).ifPresent(service::setTraceIdProvider);
        return service;
    }

    /**
     * The GitHub panel. Repository detection is local — it reads the working directory's git configuration —
     * and the API client is only ever exercised by the explicit refresh action, against an allow-listed host
     * and within the configured call and quota bounds.
     */
    @Singleton
    GitHubDashboardService gitHubDashboardService(Environment environment) {
        boolean apiEnabled = environment
                .getProperty("bootui.github.api-enabled", Boolean.class)
                .orElse(Boolean.TRUE);
        MicronautGitHubSettings settings = MicronautGitHubSettings.from(environment);
        GitHubApiClient client = new GitHubApiClient(
                settings,
                HttpClient.newBuilder()
                        .connectTimeout(settings.requestTimeout())
                        .build(),
                new ObjectMapper(),
                DefaultGitHubTokenProvider.create());
        return GitHubDashboardService.using(
                Path.of(System.getProperty("user.dir", ".")),
                new GitHubDashboardConfig(apiEnabled, settings.allowedApiHosts()),
                client);
    }

    /**
     * The scheduled-task-run ring buffer the Live Activity timeline reads. Always produced; without
     * micronaut-scheduling nothing records and the timeline simply carries no scheduled entries.
     */
    @Singleton
    ScheduledTaskRunStore scheduledTaskRunStore(Environment environment) {
        return new ScheduledTaskRunStore(environment
                .getProperty("bootui.activity.max-scheduled-task-runs", Integer.class)
                .orElse(200));
    }

    /**
     * The Kafka and RabbitMQ activity recorders the Live Activity timeline reads. They hold no messaging
     * types of their own, so both are produced unconditionally and stay empty until a capture point feeds
     * them — which is why the timeline reports no messaging entries rather than failing on an application
     * that has no broker.
     */
    @Singleton
    KafkaActivityRecorder kafkaActivityRecorder(Environment environment) {
        boolean enabled =
                environment.getProperty("bootui.kafka.enabled", Boolean.class).orElse(true)
                        && environment
                                .getProperty("bootui.panels.kafka.enabled", Boolean.class)
                                .orElse(true);
        return new KafkaActivityRecorder(
                enabled,
                environment
                        .getProperty("bootui.kafka.capture-key", Boolean.class)
                        .orElse(true),
                environment
                        .getProperty("bootui.kafka.max-entries", Integer.class)
                        .orElse(200),
                environment
                        .getProperty("bootui.kafka.max-key-length", Integer.class)
                        .orElse(16));
    }

    @Singleton
    RabbitActivityRecorder rabbitActivityRecorder(Environment environment) {
        boolean enabled = environment
                        .getProperty("bootui.rabbitmq.enabled", Boolean.class)
                        .orElse(true)
                && environment
                        .getProperty("bootui.panels.rabbitmq.enabled", Boolean.class)
                        .orElse(true);
        return new RabbitActivityRecorder(
                enabled,
                environment
                        .getProperty("bootui.rabbitmq.capture-correlation-id", Boolean.class)
                        .orElse(false),
                environment
                        .getProperty("bootui.rabbitmq.max-entries", Integer.class)
                        .orElse(200),
                environment
                        .getProperty("bootui.rabbitmq.max-correlation-id-length", Integer.class)
                        .orElse(16));
    }

    /**
     * The Live Activity persistence settings, resolved once so the store and the capture poller can never
     * disagree — in particular about the generated instance id, which scopes every stored entry.
     */
    @Singleton
    ActivityPersistenceSettings activityPersistenceSettings(Environment environment) {
        boolean enabled = environment
                .getProperty("bootui.activity.persistence.enabled", Boolean.class)
                .orElse(Boolean.FALSE);
        String dataSourceModeValue = environment
                .getProperty("bootui.activity.persistence.data-source-mode", String.class)
                .orElse("SHARED");
        ActivityPersistenceSettings.DataSourceMode dataSourceMode = "DEDICATED".equalsIgnoreCase(dataSourceModeValue)
                ? ActivityPersistenceSettings.DataSourceMode.DEDICATED
                : ActivityPersistenceSettings.DataSourceMode.SHARED;
        String instanceId = ActivityInstanceIds.resolveOrDefault(
                environment
                        .getProperty("bootui.activity.persistence.instance-id", String.class)
                        .orElse(null),
                environment
                        .getProperty("micronaut.application.name", String.class)
                        .orElse(null));
        return new ActivityPersistenceSettings(
                enabled,
                dataSourceMode,
                environment
                        .getProperty("bootui.activity.persistence.dedicated-jdbc-url", String.class)
                        .orElse(null),
                environment
                        .getProperty("bootui.activity.persistence.dedicated-username", String.class)
                        .orElse(null),
                environment
                        .getProperty("bootui.activity.persistence.dedicated-password", String.class)
                        .orElse(null),
                environment
                        .getProperty("bootui.activity.persistence.dedicated-driver-class-name", String.class)
                        .orElse(null),
                environment
                        .getProperty("bootui.activity.persistence.table-name", String.class)
                        .orElse("bootui_activity"),
                environment
                        .getProperty("bootui.activity.persistence.flush-interval", Duration.class)
                        .orElse(Duration.ofSeconds(5)),
                environment
                        .getProperty("bootui.activity.persistence.buffer-max-entries", Integer.class)
                        .orElse(500),
                environment
                        .getProperty("bootui.activity.persistence.retention", Duration.class)
                        .orElse(Duration.ofDays(7)),
                instanceId,
                environment
                        .getProperty("bootui.activity.persistence.capture-interval", Duration.class)
                        .orElse(Duration.ofSeconds(2)));
    }

    /**
     * The Live Activity store. The factory itself branches on whether persistence is enabled, returning a
     * plain in-memory store when it is not — so no background thread, connection or JDBC type is touched by
     * default. The store is closed with the context.
     */
    @Singleton
    @io.micronaut.context.annotation.Bean(preDestroy = "close")
    SwitchableActivityStore activityStore(
            ActivityPersistenceSettings settings, MicronautDataSourceProvider dataSources) {
        return ActivityStoreFactory.create(
                settings,
                () -> dataSources.dataSources().stream()
                        .map(io.github.jdubois.bootui.spi.NamedDataSource::dataSource)
                        .findFirst()
                        .orElse(null));
    }

    /**
     * The store of advisor rules the developer has dismissed, kept next to the BootUI overrides file so a
     * dismissal survives a restart. Mirrors the Quarkus adapter's location resolution key for key.
     */
    @Singleton
    DismissedRulesStore dismissedRulesStore(Environment environment) {
        String overridesFile = environment
                .getProperty("bootui.overrides-file", String.class)
                .orElse(".bootui/application-bootui.properties");
        Path parent = (overridesFile != null && !overridesFile.isBlank())
                ? Paths.get(overridesFile).getParent()
                : null;
        String dir = (parent != null) ? parent.toString() : ".bootui";
        return new DismissedRulesStore(Paths.get(dir, "boot-ui.yml"));
    }
}
