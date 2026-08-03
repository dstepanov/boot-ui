package io.github.jdubois.bootui.autoconfigure.security;

import io.github.jdubois.bootui.autoconfigure.security.ReactiveSecurityModel.CorsConfigModel;
import io.github.jdubois.bootui.autoconfigure.security.ReactiveSecurityModel.WebFilterChainModel;
import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import io.github.jdubois.bootui.core.dto.SecurityScanStatusDto;
import io.github.jdubois.bootui.core.dto.SecuritySeverityCountDto;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.web.server.WebFilter;

/**
 * Bounded, on-demand Spring Security WebFlux (reactive) advisor.
 *
 * <p>The scanner reads the registered {@code SecurityWebFilterChain} beans via the
 * {@code WebFilterChainProxy}, builds a read-only model, and runs a curated registry of static
 * best-practice checks. It never intercepts live requests and never surfaces credentials, keys, or
 * session identifiers.</p>
 */
public final class ReactiveSecurityScanner {

    private static final String ANALYZER = "BootUI Spring Security Advisor (Reactive)";
    private static final String DISCLAIMER =
            "Heuristic Spring Security WebFlux rules run against the host application's registered "
                    + "security web filter chains and security beans only. These checks are review prompts, "
                    + "not verdicts, and should be validated against the application's threat model.";
    private static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
    private static final Duration FILTER_COLLECT_TIMEOUT = Duration.ofSeconds(5);

    private static final Comparator<SecurityRuleResultDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (SecurityRuleResultDto result) -> severityRank(result.severity()))
            .thenComparing(
                    Comparator.comparingInt(SecurityRuleResultDto::violationCount).reversed())
            .thenComparing(SecurityRuleResultDto::id);

    private final Supplier<ReactiveDiscovery> discoverySupplier;
    private final Clock clock;

    /** Primary constructor for production use: wires against the live Spring context. */
    public ReactiveSecurityScanner(
            ObjectProvider<WebFilterChainProxy> chainProxies,
            ObjectProvider<ListableBeanFactory> beanFactories,
            Environment environment,
            Clock clock) {
        this(() -> discover(chainProxies, beanFactories, environment), clock);
    }

    /** Test constructor: allows injecting a pre-built context directly. */
    ReactiveSecurityScanner(ReactiveSecurityContext context, Clock clock) {
        this(() -> new ReactiveDiscovery(context, List.of()), clock);
    }

    private ReactiveSecurityScanner(Supplier<ReactiveDiscovery> discoverySupplier, Clock clock) {
        this.discoverySupplier = discoverySupplier;
        this.clock = clock;
    }

    /** Returns a placeholder report before the first scan has been triggered. */
    public SecurityReport initialReport() {
        return report(
                "NOT_SCANNED",
                "Security Advisor has not run yet. Click Run security checks to inspect the filter chains.",
                null,
                0,
                0,
                List.of());
    }

    /** Performs the full scan and returns the result. */
    public SecurityReport scan() {
        ReactiveDiscovery discovery = safeDiscovery();
        ReactiveSecurityContext context = discovery.context();
        if (context == null) {
            String message = discovery.errors().isEmpty()
                    ? "No Spring Security WebFilterChainProxy was found to inspect."
                    : "Spring Security reactive configuration could not be read: "
                            + String.join("; ", discovery.errors());
            return report("DISABLED", message, clock.millis(), 0, 0, List.of());
        }

        List<SecurityRuleResultDto> results = ReactiveSecurityRuleRegistry.activeRules().stream()
                .map(rule -> rule.evaluate(context))
                .toList();
        int chains = context.chains().size();
        String status = discovery.errors().isEmpty() ? "SCANNED" : "PARTIAL";
        String message = "Security Advisor completed against " + chains + " security web filter chain"
                + (chains == 1 ? "." : "s.");
        if (!discovery.errors().isEmpty()) {
            message += " Some configuration could not be read: " + String.join("; ", discovery.errors());
        }
        return report(status, message, clock.millis(), chains, results.size(), results);
    }

    /** Applies dismissals to the given report and returns the updated report. */
    public SecurityReport applyDismissals(SecurityReport report, Set<String> dismissedIds) {
        if (report == null || dismissedIds == null || dismissedIds.isEmpty()) {
            return report;
        }
        List<SecurityRuleResultDto> marked = report.results().stream()
                .map(result -> result.withDismissed(dismissedIds.contains(result.id())))
                .toList();
        List<SecurityRuleResultDto> active =
                marked.stream().filter(result -> !result.dismissed()).toList();
        int violationsFound = active.size();
        SecurityScanStatusDto scan = report.scan();
        SecurityScanStatusDto updatedScan = new SecurityScanStatusDto(
                scan.analyzer(),
                scan.status(),
                scan.message(),
                scan.scannedAt(),
                scan.rulesEvaluated(),
                scan.filterChainsAnalyzed(),
                violationsFound);
        return new SecurityReport(
                report.localOnly(),
                report.disclaimer(),
                report.filterChains(),
                report.filterChainsAnalyzed(),
                report.rulesEvaluated(),
                violationsFound,
                severityCounts(active),
                updatedScan,
                marked,
                report.analysisErrors());
    }

    // ── Internal helpers ──────────────────────────────────────────────────────────

    private volatile ReactiveSecurityContext lastContext;

    private ReactiveDiscovery safeDiscovery() {
        try {
            ReactiveDiscovery discovery = discoverySupplier.get();
            if (discovery == null) {
                return ReactiveDiscovery.empty("No Spring Security WebFilterChainProxy is available.");
            }
            lastContext = discovery.context();
            return discovery;
        } catch (RuntimeException | LinkageError ex) {
            return ReactiveDiscovery.empty(safeMessage(ex));
        }
    }

    private SecurityReport report(
            String status,
            String message,
            Long scannedAt,
            int filterChainsAnalyzed,
            int rulesEvaluated,
            List<SecurityRuleResultDto> results) {
        List<SecurityRuleResultDto> violations = violationResults(results);
        int violationsFound = violations.size();
        SecurityScanStatusDto scan = new SecurityScanStatusDto(
                ANALYZER, status, message, scannedAt, rulesEvaluated, filterChainsAnalyzed, violationsFound);
        return new SecurityReport(
                true,
                DISCLAIMER,
                chainDescriptions(lastContext),
                filterChainsAnalyzed,
                rulesEvaluated,
                violationsFound,
                severityCounts(violations),
                scan,
                violations,
                analysisErrors(results));
    }

    private static List<String> chainDescriptions(ReactiveSecurityContext context) {
        if (context == null) {
            return List.of();
        }
        return context.chains().stream().map(WebFilterChainModel::matcher).toList();
    }

    // ── Discovery ──────────────────────────────────────────────────────────────────

    private static ReactiveDiscovery discover(
            ObjectProvider<WebFilterChainProxy> chainProxies,
            ObjectProvider<ListableBeanFactory> beanFactories,
            Environment environment) {
        WebFilterChainProxy proxy;
        try {
            proxy = chainProxies.getIfAvailable();
        } catch (RuntimeException | LinkageError ex) {
            return ReactiveDiscovery.empty(safeMessage(ex));
        }
        if (proxy == null) {
            return ReactiveDiscovery.empty("No Spring Security WebFilterChainProxy is available.");
        }

        List<String> errors = new ArrayList<>();
        List<WebFilterChainModel> chains = new ArrayList<>();
        List<SecurityWebFilterChain> securityChains = extractChains(proxy, errors);

        for (int i = 0; i < securityChains.size(); i++) {
            try {
                chains.add(toChainModel(i, securityChains.get(i)));
            } catch (RuntimeException | LinkageError ex) {
                errors.add("Chain " + i + ": " + safeMessage(ex));
            }
        }

        ListableBeanFactory beanFactory = beanFactories.getIfAvailable();
        List<String> reactiveJwtDecoderTypes =
                beanTypeNames(beanFactory, "org.springframework.security.oauth2.jwt.ReactiveJwtDecoder");
        List<String> oauth2TokenValidatorTypes = beanTypeNames(
                beanFactory, "org.springframework.security.oauth2.core.OAuth2TokenValidator");
        List<String> opaqueTokenIntrospectorTypes = beanTypeNames(
                beanFactory,
                "org.springframework.security.oauth2.server.resource.introspection.ReactiveOpaqueTokenIntrospector");

        List<CorsConfigModel> corsConfigs = new ArrayList<>();
        discoverCors(beanFactory, corsConfigs, errors);

        ReactiveSecurityContext context = new ReactiveSecurityContext(
                chains,
                corsConfigs,
                !corsConfigs.isEmpty(),
                reactiveJwtDecoderTypes,
                oauth2TokenValidatorTypes,
                opaqueTokenIntrospectorTypes,
                environment);
        return new ReactiveDiscovery(context, errors);
    }

    @SuppressWarnings("unchecked")
    private static List<SecurityWebFilterChain> extractChains(WebFilterChainProxy proxy, List<String> errors) {
        // WebFilterChainProxy stores its chains in a field named "filters" (a List<? extends SecurityWebFilterChain>).
        Object raw = readField(proxy, "filters");
        if (raw instanceof List<?> list) {
            try {
                return (List<SecurityWebFilterChain>) list;
            } catch (ClassCastException ex) {
                errors.add("Filter chain list could not be cast: " + safeMessage(ex));
            }
        }
        errors.add("Could not extract filter chain list from WebFilterChainProxy (field 'filters' not found or null).");
        return List.of();
    }

    private static WebFilterChainModel toChainModel(int index, SecurityWebFilterChain chain) {
        String matcher = matcherDescription(chain);
        List<String> webFilterNames = extractFilterNames(chain);
        Boolean permitsAllAnonymous = detectPermitsAllAnonymous(webFilterNames);
        HeaderWriterInfo headerWriters = detectHeaderWriters(chain, webFilterNames);
        return new WebFilterChainModel(
                index,
                matcher,
                webFilterNames,
                permitsAllAnonymous,
                headerWriters.names(),
                headerWriters.hstsMaxAgeSeconds(),
                headerWriters.hstsIncludeSubdomains(),
                headerWriters.cspPolicyDirectives(),
                headerWriters.cspReportOnly());
    }

    private static String matcherDescription(SecurityWebFilterChain chain) {
        // Try the standard MatcherSecurityWebFilterChain matcher field first.
        Object matcher = readField(chain, "securityMatcher");
        if (matcher != null) {
            return String.valueOf(matcher);
        }
        return "(custom chain: " + chain.getClass().getSimpleName() + ")";
    }

    private static List<String> extractFilterNames(SecurityWebFilterChain chain) {
        // Fast path: reflect the "filters" List<WebFilter> field directly from the chain.
        Object raw = readField(chain, "filters");
        if (raw instanceof List<?> list) {
            List<String> names = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof WebFilter webFilter) {
                    names.add(webFilter.getClass().getSimpleName());
                }
            }
            if (!names.isEmpty()) {
                return names;
            }
        }
        // Fallback: subscribe with a short timeout (must not be on the event loop).
        try {
            List<WebFilter> filters =
                    chain.getWebFilters().collectList().block(FILTER_COLLECT_TIMEOUT);
            if (filters != null) {
                return filters.stream().map(f -> f.getClass().getSimpleName()).toList();
            }
        } catch (RuntimeException | LinkageError ex) {
            // ignore — return empty list
        }
        return List.of();
    }

    /**
     * A reactive chain with no {@code AuthorizationWebFilter} permits all requests without
     * authorization (analogous to not having {@code authorizeExchange(...)} at all).
     */
    private static Boolean detectPermitsAllAnonymous(List<String> filterNames) {
        return !filterNames.contains("AuthorizationWebFilter");
    }

    // ── Header writer extraction ───────────────────────────────────────────────────

    private record HeaderWriterInfo(
            List<String> names,
            Long hstsMaxAgeSeconds,
            Boolean hstsIncludeSubdomains,
            String cspPolicyDirectives,
            Boolean cspReportOnly) {}

    private static HeaderWriterInfo detectHeaderWriters(SecurityWebFilterChain chain, List<String> filterNames) {
        if (!filterNames.contains("HttpHeaderWriterWebFilter")) {
            return new HeaderWriterInfo(List.of(), null, null, null, null);
        }
        // Reflect the HttpHeaderWriterWebFilter from the chain's filter list.
        Object rawFilters = readField(chain, "filters");
        if (!(rawFilters instanceof List<?> filters)) {
            return new HeaderWriterInfo(List.of(), null, null, null, null);
        }
        for (Object filter : filters) {
            if (filter == null
                    || !"HttpHeaderWriterWebFilter".equals(filter.getClass().getSimpleName())) {
                continue;
            }
            return readHeaderWriterFilter(filter);
        }
        return new HeaderWriterInfo(List.of(), null, null, null, null);
    }

    private static HeaderWriterInfo readHeaderWriterFilter(Object headerFilter) {
        Object writerField = readField(headerFilter, "headerWriter");
        if (writerField == null) {
            return new HeaderWriterInfo(List.of(), null, null, null, null);
        }
        List<Object> writers = flattenWriters(writerField);
        List<String> names = writers.stream()
                .map(w -> w.getClass().getSimpleName())
                .toList();
        Long hstsMaxAge = null;
        Boolean hstsIncludeSubdomains = null;
        String cspDirectives = null;
        Boolean cspReportOnly = null;
        for (Object writer : writers) {
            String simpleName = writer.getClass().getSimpleName();
            if (simpleName.contains("Hsts")) {
                Object maxAge = readField(writer, "maxAgeInSeconds");
                if (maxAge instanceof Number n) {
                    hstsMaxAge = n.longValue();
                }
                Object includeSubs = readField(writer, "includeSubDomains");
                if (includeSubs instanceof Boolean b) {
                    hstsIncludeSubdomains = b;
                }
            } else if (simpleName.contains("ContentSecurityPolicy")) {
                Object policy = readField(writer, "policyDirectives");
                cspDirectives = policy == null ? null : String.valueOf(policy);
                Object reportOnly = readField(writer, "reportOnly");
                if (reportOnly instanceof Boolean b) {
                    cspReportOnly = b;
                }
            }
        }
        return new HeaderWriterInfo(names, hstsMaxAge, hstsIncludeSubdomains, cspDirectives, cspReportOnly);
    }

    private static List<Object> flattenWriters(Object writerField) {
        // CompositeServerHttpHeadersWriter or DelegatingServerHttpHeadersWriter wraps a list.
        Object delegateList = readField(writerField, "headerWriters");
        if (delegateList instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(item);
                }
            }
            return result;
        }
        // Single writer
        return List.of(writerField);
    }

    // ── CORS discovery ────────────────────────────────────────────────────────────

    private static void discoverCors(
            ListableBeanFactory beanFactory, List<CorsConfigModel> corsConfigs, List<String> errors) {
        if (beanFactory == null) {
            return;
        }
        Class<?> sourceType = classForName("org.springframework.web.cors.reactive.CorsConfigurationSource");
        if (sourceType == null) {
            return;
        }
        Map<String, ?> beans;
        try {
            beans = beanFactory.getBeansOfType(sourceType);
        } catch (RuntimeException | LinkageError ex) {
            errors.add("CORS: " + safeMessage(ex));
            return;
        }
        for (Object bean : beans.values()) {
            if (bean == null) {
                continue;
            }
            // Try to extract URL-mapped CORS configurations via reflection.
            Object corsConfigurationsField = readField(bean, "corsConfigurations");
            if (corsConfigurationsField instanceof Map<?, ?> corsMap) {
                for (Map.Entry<?, ?> entry : corsMap.entrySet()) {
                    CorsConfigModel model = toCorsModel(String.valueOf(entry.getKey()), entry.getValue());
                    if (model != null) {
                        corsConfigs.add(model);
                    }
                }
            }
        }
    }

    private static CorsConfigModel toCorsModel(String pattern, Object config) {
        if (config == null) {
            return null;
        }
        Object allowedOriginsField = readField(config, "allowedOrigins");
        Object allowedOriginPatternsField = readField(config, "allowedOriginPatterns");
        Object allowedMethodsField = readField(config, "allowedMethods");
        Object allowedHeadersField = readField(config, "allowedHeaders");
        Object allowCredentialsField = readField(config, "allowCredentials");
        return new CorsConfigModel(
                pattern,
                toStringList(allowedOriginsField),
                toStringList(allowedOriginPatternsField),
                toStringList(allowedMethodsField),
                toStringList(allowedHeadersField),
                allowCredentialsField instanceof Boolean b ? b : null);
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object obj) {
        if (obj instanceof List<?> list) {
            try {
                return (List<String>) list;
            } catch (ClassCastException ex) {
                return list.stream().map(String::valueOf).toList();
            }
        }
        return List.of();
    }

    // ── Reflection helpers ────────────────────────────────────────────────────────

    static Object readField(Object target, String fieldName) {
        Class<?> current = target.getClass();
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ex) {
                current = current.getSuperclass();
            } catch (RuntimeException | LinkageError | IllegalAccessException ex) {
                return null;
            }
        }
        return null;
    }

    private static List<String> beanTypeNames(ListableBeanFactory beanFactory, String className) {
        if (beanFactory == null) {
            return List.of();
        }
        Class<?> type = classForName(className);
        if (type == null) {
            return List.of();
        }
        try {
            String[] names = beanFactory.getBeanNamesForType(type);
            List<String> result = new ArrayList<>();
            for (String name : names) {
                try {
                    Class<?> beanType = beanFactory.getType(name);
                    result.add(beanType == null ? name : beanType.getName());
                } catch (RuntimeException | LinkageError ex) {
                    result.add(name);
                }
            }
            return result;
        } catch (RuntimeException | LinkageError ex) {
            return List.of();
        }
    }

    private static Class<?> classForName(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException | LinkageError ex) {
            return null;
        }
    }

    private static String safeMessage(Throwable ex) {
        return ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
    }

    // ── Aggregation ───────────────────────────────────────────────────────────────

    private List<SecuritySeverityCountDto> severityCounts(List<SecurityRuleResultDto> results) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String severity : SEVERITIES) {
            counts.put(severity, 0);
        }
        for (SecurityRuleResultDto result : results) {
            if (isViolation(result)) {
                counts.computeIfPresent(result.severity(), (ignored, count) -> count + 1);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new SecuritySeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<SecurityRuleResultDto> violationResults(List<SecurityRuleResultDto> results) {
        return results.stream()
                .filter(ReactiveSecurityScanner::isViolation)
                .sorted(IMPORTANCE_ORDER)
                .toList();
    }

    static List<SecurityRuleResultDto> analysisErrors(List<SecurityRuleResultDto> results) {
        return results.stream()
                .filter(result -> SecurityRuleSupport.ERROR.equals(result.status()))
                .sorted(Comparator.comparing(SecurityRuleResultDto::id))
                .toList();
    }

    private static int severityRank(String severity) {
        int index = SEVERITIES.indexOf(severity);
        return index >= 0 ? index : SEVERITIES.size();
    }

    private static boolean isViolation(SecurityRuleResultDto result) {
        return SecurityRuleSupport.VIOLATION.equals(result.status());
    }

    private record ReactiveDiscovery(ReactiveSecurityContext context, List<String> errors) {

        ReactiveDiscovery {
            errors = List.copyOf(errors);
        }

        static ReactiveDiscovery empty(String reason) {
            return new ReactiveDiscovery(null, List.of(reason == null ? "Unavailable." : reason));
        }
    }
}
