package io.github.jdubois.bootui.micronaut.beans;

import io.github.jdubois.bootui.core.dto.BeanSummary;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.github.jdubois.bootui.micronaut.MicronautBeanTypes;
import io.github.jdubois.bootui.spi.BeanProvider;
import io.micronaut.context.BeanContext;
import io.micronaut.inject.BeanDefinition;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Micronaut {@link BeanProvider} backed by the compile-time bean container.
 *
 * <p>The Micronaut analogue of the Spring adapter's {@code SpringBeanProvider} (which reads Actuator's
 * {@code BeansEndpoint}) and of the Quarkus adapter's {@code QuarkusBeanProvider} (which reads Arc/CDI).
 * It enumerates the container's {@link BeanDefinition}s, maps each to a neutral {@link BeanSummary},
 * applies BootUI's self-data filter (dropping the adapter's own beans) and computes the Micronaut-flavored
 * classification. The engine {@code BeansService} then sorts, classification/free-text filters and pages on
 * top.
 *
 * <p>Unlike the Quarkus adapter — which has to capture injection edges during build-time augmentation
 * because Arc does not expose them at runtime — Micronaut's {@link BeanDefinition} carries its own
 * dependency metadata, so edges are read live from {@link BeanDefinition#getRequiredComponents()} with no
 * build-time capture step. Reduced fidelity vs Spring is limited to two fields, and is reported honestly:
 * Micronaut has no notion of a bean's defining resource, so {@code resource} remains empty, and
 * {@code scope} uses the Micronaut/Jakarta scope vocabulary ({@code Singleton}, {@code Prototype},
 * {@code Context}, {@code RequestScope}, …) rather than Spring's {@code singleton}/{@code prototype}.
 * {@code name} is the bean's {@code @Named} value when it has one, otherwise the decapitalized simple type
 * name, matching how the other adapters name unnamed container beans. Qualifiers have no
 * {@link BeanSummary} field — the DTO is the frozen UI contract — so {@code aliases} is always empty.
 *
 * <p>The self-filter reuses the shared engine {@link InternalPackageMatcher} scoped to
 * {@code io.github.jdubois.bootui.micronaut}/{@code .core} rather than the whole
 * {@code io.github.jdubois.bootui} tree, so it does not also swallow application code that happens to live
 * under that root package, nor the framework-neutral {@code engine}/{@code spi} packages.
 */
public final class MicronautBeanProvider implements BeanProvider {

    private static final InternalPackageMatcher INTERNAL_PACKAGES =
            new InternalPackageMatcher(List.of("io.github.jdubois.bootui.micronaut", "io.github.jdubois.bootui.core"));

    private static final List<String> FRAMEWORK_PREFIXES = List.of("io.micronaut.", "io.netty.", "reactor.core.");

    private final BeanContext beanContext;

    public MicronautBeanProvider(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public boolean available() {
        // The bean container is the adapter's own runtime, so it is always present.
        return beanContext != null;
    }

    @Override
    public List<BeanSummary> beans() {
        if (beanContext == null) {
            return List.of();
        }
        List<BeanSummary> summaries = new ArrayList<>();
        Map<String, List<String>> dependenciesByName = new java.util.LinkedHashMap<>();
        for (BeanDefinition<?> definition : beanContext.getAllBeanDefinitions()) {
            Class<?> beanType = MicronautBeanTypes.resolve(definition);
            String type = beanType == null ? null : beanType.getName();
            if (type != null && INTERNAL_PACKAGES.matchesName(type)) {
                continue;
            }
            String name = name(definition, beanType);
            summaries.add(new BeanSummary(name, type, scope(definition), null, List.of(), List.of(), classify(type)));
            dependenciesByName
                    .computeIfAbsent(name, ignored -> new ArrayList<>())
                    .addAll(dependencyNames(definition));
        }
        Set<String> visibleNames = summaries.stream().map(BeanSummary::name).collect(Collectors.toSet());
        return summaries.stream()
                .map(summary -> withDependencies(summary, dependenciesByName, visibleNames))
                .toList();
    }

    /**
     * The names of the beans this definition injects, expressed in the same naming scheme as
     * {@link #name}, so the graph contract's name-based edges line up with the inventory.
     */
    private List<String> dependencyNames(BeanDefinition<?> definition) {
        try {
            return definition.getRequiredComponents().stream()
                    .filter(component -> component != null)
                    .map(component -> decapitalize(component.getSimpleName()))
                    .toList();
        } catch (RuntimeException ex) {
            // A definition whose dependency metadata cannot be read must not fail the whole inventory.
            return List.of();
        }
    }

    private BeanSummary withDependencies(
            BeanSummary summary, Map<String, List<String>> dependenciesByName, Set<String> visibleNames) {
        List<String> visibleDependencies =
                new ArrayList<>(new LinkedHashSet<>(dependenciesByName.getOrDefault(summary.name(), List.of())));
        visibleDependencies.removeIf(name -> name.equals(summary.name()) || !visibleNames.contains(name));
        visibleDependencies.sort(String::compareTo);
        return new BeanSummary(
                summary.name(),
                summary.type(),
                summary.scope(),
                summary.resource(),
                List.copyOf(visibleDependencies),
                summary.aliases(),
                summary.classification());
    }

    private String name(BeanDefinition<?> definition, Class<?> beanType) {
        String named = definition
                .getAnnotationMetadata()
                .stringValue(jakarta.inject.Named.class)
                .orElse(null);
        if (named != null && !named.isBlank()) {
            return named;
        }
        if (beanType == null) {
            return definition.getName();
        }
        return decapitalize(beanType.getSimpleName());
    }

    private String scope(BeanDefinition<?> definition) {
        return definition
                .getScope()
                .map(Class::getSimpleName)
                .orElseGet(() -> definition.isSingleton() ? "Singleton" : "Prototype");
    }

    private String classify(String type) {
        if (type == null) {
            return "OTHER";
        }
        if (INTERNAL_PACKAGES.matchesName(type)) {
            return "BOOTUI";
        }
        for (String prefix : FRAMEWORK_PREFIXES) {
            if (type.startsWith(prefix)) {
                return "FRAMEWORK";
            }
        }
        if (type.startsWith("java.") || type.startsWith("jakarta.")) {
            return "PLATFORM";
        }
        return "APPLICATION";
    }

    private static String decapitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.substring(0, 1).toLowerCase(Locale.ROOT) + value.substring(1);
    }
}
