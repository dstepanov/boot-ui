package io.github.jdubois.bootui.micronaut;

import io.github.jdubois.bootui.core.dto.PanelDto;
import io.github.jdubois.bootui.core.dto.PanelsReport;
import io.github.jdubois.bootui.engine.github.GitHubRepositoryDetector;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.micronaut.agent.MicronautClaudeCodeSessionStore;
import io.github.jdubois.bootui.micronaut.agent.MicronautCopilotSessionStore;
import io.github.jdubois.bootui.micronaut.flyway.MicronautFlywayProvider;
import io.github.jdubois.bootui.micronaut.github.MicronautGitHubSettings;
import io.github.jdubois.bootui.micronaut.liquibase.MicronautLiquibaseProvider;
import io.github.jdubois.bootui.spi.FlywayProvider;
import io.github.jdubois.bootui.spi.LiquibaseProvider;
import io.micronaut.context.BeanContext;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes the BootUI panel manifest for the Micronaut adapter.
 *
 * <p>The panel registry ({@link BootUiPanels}) is shared with the Spring and Quarkus adapters, so panel
 * ids, titles and order are identical and the shared Vue UI renders the same sidebar everywhere.
 * Availability is platform-specific and computed here.
 *
 * <p>This release lights up the framework-neutral panels — Threads, Heap Dump, Live Memory, JVM Tuning and
 * the Memory advisor (which aggregate JMX heap/GC/thread/class-loading data and run the shared static rule
 * registry on demand), the HTTP Probe panel (which probes the application's own loopback port), and the
 * Architecture (ArchUnit) advisor (which bounds its bytecode import to the base packages Micronaut deduced
 * for the application and runs the shared curated rule registry on demand) — plus the four panels with a
 * genuinely Micronaut-native binding: <strong>Beans</strong> reads the live bean container through
 * {@code MicronautBeanProvider}, <strong>Mappings</strong> reads the live {@code Router},
 * <strong>Configuration</strong> reads the {@code Environment}'s property sources, and
 * <strong>Loggers</strong> reads and writes Logback levels. <strong>Metrics</strong> and
 * <strong>Health</strong> are always available and honest about their backing: Metrics reports real
 * Micrometer meters when {@code micronaut-micrometer} is present and otherwise renders as unavailable, and
 * Health reports the real aggregated indicators when {@code micronaut-management} is present and otherwise
 * renders setup guidance.
 *
 * <p>Every other panel is reported unavailable with a clear, honest reason, distinguishing the two cases
 * the shared UI renders differently: {@link #NOT_APPLICABLE} for a capability that has no Micronaut
 * analogue at all, and {@link #NOT_YET_AVAILABLE} for one whose Micronaut binding simply has not been
 * written yet.
 *
 * <p>Two panels are gated on a <em>configured</em> integration rather than a present one. Flyway and
 * Liquibase light up only when their library is on the classpath <em>and</em> at least one enabled
 * {@code flyway.datasources.<name>} / {@code liquibase.datasources.<name>} configuration is backed by a
 * {@code DataSource} bean of the same name — the Micronaut analogue of the Spring adapter's "a Flyway /
 * SpringLiquibase bean exists" rule (Quarkus decides from its build-time capability instead, and its sample
 * always configures a datasource). The decision is read from the very {@link MicronautFlywayProvider} /
 * {@link MicronautLiquibaseProvider} logic the engine services run on, so the manifest can never advertise a
 * panel whose reads report {@code flywayPresent:false} and whose actions answer 404. A library that is
 * present but unconfigured names the missing configuration rather than the missing dependency.
 *
 * <p>Action-capable panels here are Loggers (a logger level can be set), Heap Dump (it captures and
 * deletes dumps), HTTP Probe (it issues a request), Architecture (it runs a scan and dismisses rules),
 * Threads (the raw dump download is modelled as an action) and the Memory advisor (it runs a scan).
 * Per-panel enabled/read-only gating is modelled exactly like the other adapters: {@code enabled} reflects
 * the live {@code bootui.panels.<id>.enabled} config (default {@code true}), and {@code readOnly} /
 * {@code readOnlyReason} reflect the OR of the live {@code bootui.panels.<id>.read-only} / global
 * {@code bootui.read-only} config (default {@code false}) with the Configuration panel's own inherent
 * read-only-ness; both are computed by {@link MicronautPanelAccessConfig}, shared with
 * {@link MicronautPanelAccessFilter} so the manifest and the enforcing filter can never disagree.
 */
@RequiresBootUi
@Singleton
public class MicronautPanelAvailability {

    private static final String NOT_YET_AVAILABLE = "Not yet available on Micronaut.";

    /**
     * Panels that are permanently not applicable on Micronaut because the capability has no Micronaut
     * equivalent — as opposed to the generic {@link #NOT_YET_AVAILABLE} panels, which simply have not been
     * ported yet. Each maps to an honest, panel-specific reason.
     */
    private static final Map<String, String> NOT_APPLICABLE = Map.of(
            BootUiPanels.GRAALVM,
            "Not applicable on Micronaut: Micronaut generates its own GraalVM reachability metadata at"
                    + " compile time and is native-image-first by design, so this Spring-oriented native-image"
                    + " readiness advisor is not used here.",
            BootUiPanels.CRAC,
            "Not applicable on Micronaut: this CRaC checkpoint/restore advisor targets the Spring Boot startup"
                    + " model, and Micronaut's fast startup comes from compile-time dependency injection and"
                    + " native images instead, so it is not used here.",
            BootUiPanels.CONDITIONS,
            "Not applicable on Micronaut: Spring Conditions reports @ConditionalOn… auto-configuration match"
                    + " outcomes, but Micronaut evaluates @Requires against compile-time bean definitions and"
                    + " keeps no runtime positive/negative condition-match graph to display.",
            BootUiPanels.STARTUP,
            "Not applicable on Micronaut: startup work is eliminated by compile-time dependency injection, so"
                    + " there is no runtime per-step buffer (Spring's BufferingApplicationStartup) to record a"
                    + " timeline; only coarse boot totals exist, so this fine-grained step timeline is not used"
                    + " here.",
            BootUiPanels.HTTP_SESSIONS,
            "Not applicable on Micronaut: this panel inventories servlet HTTP sessions via Spring Session's"
                    + " enumerable registry, but Micronaut is reactive/stateless by default and its optional"
                    + " micronaut-session store exposes no equivalent active-session registry to list.",
            BootUiPanels.DATA,
            "Not applicable on Micronaut: this panel enumerates Spring Data repository beans, which Micronaut"
                    + " does not run; Micronaut models persistence with Micronaut Data instead.",
            BootUiPanels.SPRING_SECURITY,
            "Not applicable on Micronaut: this panel reads Spring Security's filter chain and user store,"
                    + " which Micronaut does not run. Micronaut secures applications with micronaut-security"
                    + " instead.",
            BootUiPanels.DEVTOOLS,
            "Not applicable on Micronaut: Spring Boot DevTools restart/LiveReload has no runtime analogue."
                    + " Micronaut's own reloading is driven by the build tool's continuous mode, with no runtime"
                    + " API to read or trigger it, so this panel is not used here.",
            BootUiPanels.TRANSACTIONS,
            "Not applicable on Micronaut: transaction boundary capture relies on Spring Framework's"
                    + " TransactionExecutionListener hook, registered against ConfigurableTransactionManager"
                    + " beans. Micronaut's transaction management is compile-time AOP over its own"
                    + " SynchronousTransactionManager, which exposes no comparable per-boundary listener without"
                    + " far more invasive instrumentation, so this panel is not used here.");

    private static final Map<String, String> NOT_YET_AVAILABLE_REASONS = Map.of(
            BootUiPanels.SPRING,
            "Not yet available on Micronaut: this platform-aware application advisor has a Spring and a"
                    + " Quarkus ruleset; the Micronaut idiom ruleset has not been written yet.",
            BootUiPanels.JMS,
            "Not yet available on Micronaut: BootUI's current JMS capture targets Spring JMS (JmsTemplate and"
                    + " @JmsListener).");

    private static final Set<String> AVAILABLE_PANELS = Set.of(
            BootUiPanels.OVERVIEW,
            BootUiPanels.THREADS,
            BootUiPanels.HEAP_DUMP,
            BootUiPanels.LIVE_MEMORY,
            BootUiPanels.JVM_TUNING,
            BootUiPanels.MEMORY,
            BootUiPanels.METRICS,
            BootUiPanels.LOGGERS,
            BootUiPanels.BEANS,
            BootUiPanels.MAPPINGS,
            BootUiPanels.CONFIG,
            BootUiPanels.HEALTH,
            BootUiPanels.HTTP_PROBE,
            BootUiPanels.ARCHITECTURE,
            BootUiPanels.HTTP_EXCHANGES,
            BootUiPanels.LOG_TAIL,
            BootUiPanels.EXCEPTIONS,
            BootUiPanels.PROFILE_DIFF,
            BootUiPanels.REST_API,
            BootUiPanels.SECURITY_LOGS,
            BootUiPanels.PENTESTING,
            BootUiPanels.SCHEDULED,
            BootUiPanels.SQL_TRACE,
            BootUiPanels.DATABASE_ADVISOR,
            BootUiPanels.VULNERABILITIES,
            BootUiPanels.TRACES,
            BootUiPanels.AI,
            BootUiPanels.REST_CLIENT_TRACE,
            BootUiPanels.ACTIVITY,
            BootUiPanels.MCP_SERVER,
            BootUiPanels.CLI);

    /**
     * Reasons shown for panels that are unavailable only because the corresponding Micronaut integration is
     * missing from the application — as opposed to {@link #NOT_APPLICABLE}, which is permanent regardless of
     * what the application adds. Every dynamically-available panel has a matching entry here, so a panel can
     * never go dark without saying which dependency would light it up.
     */
    private static final String HIBERNATE_ABSENT =
            "Not available: Hibernate ORM is not on the classpath. Add micronaut-data-hibernate-jpa to"
                    + " inspect this application's entity mappings and Hibernate statistics.";

    private static final Map<String, String> CAPABILITY_ABSENT = Map.ofEntries(
            Map.entry(
                    BootUiPanels.DATABASE_CONNECTION_POOLS,
                    "Not available: this application has no HikariCP datasource. Add micronaut-jdbc-hikari and"
                            + " configure a datasource to inventory its pools."),
            Map.entry(
                    BootUiPanels.CACHE,
                    "Not available: Micronaut Cache is not on the classpath. Add micronaut-cache-caffeine (or another"
                            + " micronaut-cache provider) to inventory the application's caches."),
            Map.entry(
                    BootUiPanels.FLYWAY,
                    "Not available: micronaut-flyway is not on the classpath. Add it to inspect and run this"
                            + " application's Flyway migrations."),
            Map.entry(
                    BootUiPanels.LIQUIBASE,
                    "Not available: micronaut-liquibase is not on the classpath. Add it to inspect and run this"
                            + " application's Liquibase change sets."),
            Map.entry(BootUiPanels.HIBERNATE, HIBERNATE_ABSENT),
            Map.entry(BootUiPanels.HIBERNATE_STATISTICS, HIBERNATE_ABSENT),
            Map.entry(
                    BootUiPanels.COPILOT,
                    "Not available: no Copilot CLI session-state directory was found (default ~/.copilot/session-state)."
                            + " Point bootui.copilot.session-state-dir at it if it lives elsewhere."),
            Map.entry(
                    BootUiPanels.CLAUDE_CODE,
                    "Not available: no Claude Code session directory was found (default ~/.claude/projects). Point"
                            + " bootui.claude-code.session-state-dir at it if it lives elsewhere."),
            Map.entry(
                    BootUiPanels.WEBSOCKETS,
                    "Not available: micronaut-websocket is not on the classpath. Add it to inventory this"
                            + " application's @ServerWebSocket endpoints and their sessions."),
            Map.entry(
                    BootUiPanels.FAULT_TOLERANCE,
                    "Not available: micronaut-retry is not on the classpath. Add it to inventory this application's"
                            + " @Retryable and @CircuitBreaker policies."),
            Map.entry(
                    BootUiPanels.EMAIL,
                    "Not available: micronaut-email is not on the classpath. Add it (with a sender such as"
                            + " micronaut-email-javamail) to see the messages this application sends."));

    /** The Flyway panel's reason when {@code micronaut-flyway} is present but nothing is configured for it. */
    private static final String FLYWAY_UNCONFIGURED =
            "Not available: micronaut-flyway is on the classpath, but no enabled Flyway configuration is backed"
                    + " by a datasource. Configure flyway.datasources.<name> for a datasource of the same name to"
                    + " inspect and run this application's Flyway migrations.";

    /** The Liquibase panel's reason when {@code micronaut-liquibase} is present but nothing is configured. */
    private static final String LIQUIBASE_UNCONFIGURED =
            "Not available: micronaut-liquibase is on the classpath, but no enabled Liquibase configuration with"
                    + " a change log is backed by a datasource. Configure liquibase.datasources.<name>.change-log"
                    + " for a datasource of the same name to inspect and run this application's Liquibase change"
                    + " sets.";

    /**
     * The agent panels' reason when they are switched off outright — as opposed to the
     * {@link #CAPABILITY_ABSENT} reason, which applies when they are on (or in AUTO mode) but their session
     * directory does not exist.
     */
    private static final String COPILOT_OFF =
            "Not available: the Copilot panel is switched off via bootui.copilot.enabled=OFF.";

    private static final String CLAUDE_CODE_OFF =
            "Not available: the Claude Code panel is switched off via bootui.claude-code.enabled=OFF.";

    /** Whether HikariCP — the pool {@code micronaut-jdbc-hikari} configures — is on the classpath. */
    private static final boolean HIKARI_PRESENT = isPresent("com.zaxxer.hikari.HikariDataSource");

    /** Whether Micronaut's cache abstraction is on the classpath. */
    private static final boolean CACHE_PRESENT = isPresent("io.micronaut.cache.CacheManager");

    /** Whether micronaut-flyway is on the classpath. */
    private static final boolean FLYWAY_PRESENT = isPresent("io.micronaut.flyway.FlywayConfigurationProperties");

    /** Whether micronaut-email is on the classpath. */
    private static final boolean EMAIL_PRESENT = isPresent("io.micronaut.email.TransactionalEmailSender");

    /** Whether micronaut-retry is on the classpath. */
    private static final boolean RETRY_PRESENT = isPresent("io.micronaut.retry.annotation.Retryable");

    /** Whether micronaut-websocket is on the classpath. */
    private static final boolean WEBSOCKET_PRESENT = isPresent("io.micronaut.websocket.WebSocketSession");

    /** Whether Hibernate ORM is on the classpath. */
    private static final boolean HIBERNATE_PRESENT = isPresent("org.hibernate.SessionFactory");

    /** Whether micronaut-liquibase is on the classpath. */
    private static final boolean LIQUIBASE_PRESENT =
            isPresent("io.micronaut.liquibase.LiquibaseConfigurationProperties");

    /**
     * Panels whose availability depends on what the application actually has on its classpath. Resolved once
     * because a classpath does not change while the JVM runs. Flyway and Liquibase are deliberately absent:
     * for them a present library is necessary but not sufficient, see {@link #isPanelAvailable(String)}.
     */
    private static final Map<String, Boolean> CLASSPATH_AVAILABILITY = Map.of(
            BootUiPanels.DATABASE_CONNECTION_POOLS, HIKARI_PRESENT,
            BootUiPanels.CACHE, CACHE_PRESENT,
            BootUiPanels.HIBERNATE, HIBERNATE_PRESENT,
            BootUiPanels.HIBERNATE_STATISTICS, HIBERNATE_PRESENT,
            BootUiPanels.WEBSOCKETS, WEBSOCKET_PRESENT,
            BootUiPanels.FAULT_TOLERANCE, RETRY_PRESENT,
            BootUiPanels.EMAIL, EMAIL_PRESENT);

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, MicronautPanelAvailability.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    private static final String CONFIG_READONLY =
            "Runtime config overrides are not available on Micronaut (they target the Spring bootstrap"
                    + " property sources); properties remain fully visible.";

    private final MicronautPanelAccessConfig accessConfig;
    private final MicronautCopilotSessionStore copilotStore;
    private final MicronautClaudeCodeSessionStore claudeCodeStore;
    private final List<String> githubAllowedApiHosts;

    /**
     * The Flyway / Liquibase seams, or {@code null} when the library is absent. Built exactly as
     * {@link BootUiEngineFactory} builds the ones behind the engine services — under the same classpath guard,
     * so the optional types are never linked in an application without them — and consulted for their
     * {@code available()} answer only, which reads the live configuration and instantiates nothing.
     */
    private final FlywayProvider flywayProvider;

    private final LiquibaseProvider liquibaseProvider;

    public MicronautPanelAvailability(
            Environment environment,
            BeanContext beanContext,
            MicronautCopilotSessionStore copilotStore,
            MicronautClaudeCodeSessionStore claudeCodeStore) {
        this.accessConfig = new MicronautPanelAccessConfig(environment);
        this.copilotStore = copilotStore;
        this.claudeCodeStore = claudeCodeStore;
        this.githubAllowedApiHosts = MicronautGitHubSettings.allowedApiHosts(environment);
        this.flywayProvider = FLYWAY_PRESENT ? new MicronautFlywayProvider(beanContext) : null;
        this.liquibaseProvider = LIQUIBASE_PRESENT ? new MicronautLiquibaseProvider(beanContext, environment) : null;
    }

    private static Path workingDirectory() {
        return Path.of(System.getProperty("user.dir", "."));
    }

    public PanelsReport manifest() {
        return new PanelsReport(
                PanelsReport.PLATFORM_MICRONAUT,
                BootUiPanels.all().stream().map(this::toDto).toList());
    }

    /**
     * Whether the panel with the given id is available on this Micronaut runtime. This is the single source
     * of truth for panel availability — the manifest and any future MCP tool catalog both consult it, so a
     * tool can never be advertised while its backing panel is dark.
     */
    public boolean isPanelAvailable(String panelId) {
        if (AVAILABLE_PANELS.contains(panelId) || CLASSPATH_AVAILABILITY.getOrDefault(panelId, Boolean.FALSE)) {
            return true;
        }
        // Flyway and Liquibase are gated on configuration, not on the classpath: the library being present is
        // necessary but not sufficient. Both are re-read on every call, through the same provider logic the
        // engine services run on, so the manifest and the panel's own reads and actions can never disagree.
        if (BootUiPanels.FLYWAY.equals(panelId)) {
            return flywayProvider != null && flywayProvider.available();
        }
        if (BootUiPanels.LIQUIBASE.equals(panelId)) {
            return liquibaseProvider != null && liquibaseProvider.available();
        }
        // The two agent panels are available only when their session directory actually exists, which is a
        // filesystem fact rather than a classpath one: it can appear while the application is running, so it
        // is re-read on every call rather than memoized.
        if (BootUiPanels.COPILOT.equals(panelId)) {
            return copilotStore.isEnabled() && copilotStore.isDirectoryAvailable();
        }
        if (BootUiPanels.CLAUDE_CODE.equals(panelId)) {
            return claudeCodeStore.isEnabled() && claudeCodeStore.isDirectoryAvailable();
        }
        // GitHub availability is a fact about the working directory, not the classpath: the panel is
        // available only when the application runs from a git checkout whose origin is an allow-listed
        // GitHub host. Detection is local — it reads the git configuration and never calls the network.
        if (BootUiPanels.GITHUB.equals(panelId)) {
            return GitHubRepositoryDetector.detect(workingDirectory(), githubAllowedApiHosts)
                    .isPresent();
        }
        return false;
    }

    /** Whether the panel is enabled by the live per-panel access policy. */
    public boolean isPanelEnabled(String panelId) {
        return accessConfig.isPanelEnabled(panelId);
    }

    /** Returns the platform-specific reason for an unavailable panel, or {@code null} when available. */
    public String panelUnavailableReason(String panelId) {
        return isPanelAvailable(panelId) ? null : unavailableReason(panelId);
    }

    private PanelDto toDto(BootUiPanels.Panel panel) {
        boolean available = isPanelAvailable(panel.id());
        String unavailableReason = available ? null : unavailableReason(panel.id());
        boolean enabled = accessConfig.isPanelEnabled(panel.id());
        boolean configReadOnly = available && BootUiPanels.CONFIG.equals(panel.id());
        boolean accessReadOnly = panel.actionCapable() && accessConfig.isPanelReadOnly(panel.id());
        boolean readOnly = configReadOnly || accessReadOnly;
        String readOnlyReason = configReadOnly
                ? CONFIG_READONLY
                : (accessReadOnly ? accessConfig.panelReadOnlyReason(panel.id()) : null);
        return new PanelDto(panel.id(), panel.title(), available, unavailableReason, enabled, readOnly, readOnlyReason);
    }

    /**
     * The honest reason for a dark panel, most specific first: a library that is present but unconfigured
     * (Flyway, Liquibase), an agent panel switched off outright, a missing optional integration
     * ({@link #CAPABILITY_ABSENT}), a permanent {@link #NOT_APPLICABLE}, and finally the not-yet-ported reasons.
     */
    private String unavailableReason(String panelId) {
        if (BootUiPanels.FLYWAY.equals(panelId) && FLYWAY_PRESENT) {
            return FLYWAY_UNCONFIGURED;
        }
        if (BootUiPanels.LIQUIBASE.equals(panelId) && LIQUIBASE_PRESENT) {
            return LIQUIBASE_UNCONFIGURED;
        }
        if (BootUiPanels.COPILOT.equals(panelId) && !copilotStore.isStartEnabled()) {
            return COPILOT_OFF;
        }
        if (BootUiPanels.CLAUDE_CODE.equals(panelId) && !claudeCodeStore.isStartEnabled()) {
            return CLAUDE_CODE_OFF;
        }
        String absent = CAPABILITY_ABSENT.get(panelId);
        if (absent != null) {
            return absent;
        }
        return NOT_APPLICABLE.getOrDefault(panelId, NOT_YET_AVAILABLE_REASONS.getOrDefault(panelId, NOT_YET_AVAILABLE));
    }
}
