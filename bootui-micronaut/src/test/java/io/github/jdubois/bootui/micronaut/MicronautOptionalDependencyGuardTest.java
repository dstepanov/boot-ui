package io.github.jdubois.bootui.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins the rule that keeps this adapter loadable on an application that added only some of the integrations
 * it can describe: <em>no bean may touch an API this module declares as {@code provided} unless a
 * {@code @Requires(classes = …)} guards it</em>, on the class or on its package.
 *
 * <p>A {@code provided} dependency is on the compiler's classpath and absent from the application's, which
 * is exactly what lets a binding be written against the real API instead of reflection. The cost is that
 * nothing stops a bean from referencing one: Micronaut would create it in an application without the
 * library and fail with {@code NoClassDefFoundError} — at startup for a {@code @Context} bean, on the first
 * request for a controller. Defensive {@code catch (LinkageError)} inside such a bean does not help, because
 * the failure happens as the method that would run it is resolved.
 *
 * <p>The guard therefore has to be the bean's own existence, and it is checked here rather than left to
 * review. Declaring it in a {@code package-info.java} ({@code @Configuration} plus {@code @Requires}) is the
 * idiomatic form and the one this adapter uses — it states a feature slice's dependency once instead of on
 * every class in it — and this test accepts either form.
 *
 * <p>The second assertion closes the other half: every {@code provided} artifact in this module's POM must
 * name the package prefixes it contributes, so adding one without deciding what it makes optional fails the
 * build instead of silently escaping the first assertion.
 */
class MicronautOptionalDependencyGuardTest {

    private static final Path SOURCES = Path.of("src/main/java/io/github/jdubois/bootui/micronaut");

    private static final Path POM = Path.of("pom.xml");

    /**
     * Package prefixes each {@code provided} artifact contributes — its own, plus the API it brings in
     * transitively that a binding is written against ({@code micronaut-kafka} carries the Kafka clients,
     * {@code micronaut-rabbitmq} the AMQP client).
     */
    private static final Map<String, List<String>> OPTIONAL_PACKAGES = optionalPackages();

    private static Map<String, List<String>> optionalPackages() {
        Map<String, List<String>> packages = new LinkedHashMap<>();
        packages.put("micronaut-serde-api", List.of("io.micronaut.serde."));
        packages.put("logback-classic", List.of("ch.qos.logback."));
        packages.put("micronaut-security", List.of("io.micronaut.security."));
        packages.put("HikariCP", List.of("com.zaxxer.hikari."));
        packages.put("micronaut-cache-core", List.of("io.micronaut.cache."));
        packages.put("micronaut-flyway", List.of("io.micronaut.flyway."));
        packages.put("micronaut-liquibase", List.of("io.micronaut.liquibase."));
        packages.put("jakarta.persistence-api", List.of("jakarta.persistence."));
        packages.put("hibernate-core", List.of("org.hibernate."));
        packages.put("opentelemetry-sdk-trace", List.of("io.opentelemetry.sdk."));
        packages.put("opentelemetry-api", List.of("io.opentelemetry.api."));
        packages.put("micronaut-websocket", List.of("io.micronaut.websocket."));
        packages.put("micronaut-email", List.of("io.micronaut.email."));
        packages.put("micronaut-management", List.of("io.micronaut.management."));
        packages.put("micronaut-kafka", List.of("io.micronaut.configuration.kafka.", "org.apache.kafka."));
        packages.put("micronaut-rabbitmq", List.of("io.micronaut.rabbitmq.", "com.rabbitmq."));
        return Map.copyOf(packages);
    }

    /**
     * Type-level stereotypes that make Micronaut instantiate the class. {@code @SerdeImport} is deliberately
     * not one: {@code BootUiSerdeImports} declares compile-time introspections and is never instantiated, so
     * a Serde API it names is not loaded at runtime.
     */
    private static final List<String> BEAN_STEREOTYPES =
            List.of("@Singleton", "@Factory", "@Context", "@Controller", "@ServerFilter", "@ClientFilter", "@Filter");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\n]*");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");

    @Test
    void everyBeanTouchingAProvidedApiIsGuardedByRequires() throws IOException {
        List<String> unguarded = new ArrayList<>();
        for (Path source : javaSources()) {
            String code = withoutCommentsAndStringLiterals(Files.readString(source, StandardCharsets.UTF_8));
            if (BEAN_STEREOTYPES.stream().noneMatch(code::contains)) {
                continue;
            }
            Set<String> touched = optionalApisIn(code);
            if (touched.isEmpty() || isGuarded(source, code)) {
                continue;
            }
            unguarded.add(SOURCES.relativize(source) + " touches " + touched);
        }

        assertThat(unguarded)
                .as("a bean that references a provided API must be guarded by @Requires(classes = …), on the "
                        + "class or in its package-info, or it fails with NoClassDefFoundError on an application "
                        + "that did not add that integration")
                .isEmpty();
    }

    @Test
    void everyProvidedDependencyDeclaresThePackagesItMakesOptional() throws IOException {
        String pom = Files.readString(POM, StandardCharsets.UTF_8);
        Set<String> declared = new TreeSet<>();
        Matcher matcher = Pattern.compile(
                        "<artifactId>([^<]+)</artifactId>\\s*(?:<version>[^<]*</version>\\s*)?"
                                + "<scope>provided</scope>",
                        Pattern.DOTALL)
                .matcher(pom);
        while (matcher.find()) {
            declared.add(matcher.group(1));
        }

        assertThat(declared)
                .as("this module must declare provided dependencies for the test to be meaningful")
                .isNotEmpty();
        assertThat(OPTIONAL_PACKAGES.keySet())
                .as("every provided artifact must name the package prefixes it contributes, so the guard "
                        + "assertion above cannot be escaped by adding a dependency")
                .containsAll(declared);
    }

    private static boolean isGuarded(Path source, String code) throws IOException {
        if (code.contains("@Requires(classes")) {
            return true;
        }
        Path packageInfo = source.resolveSibling("package-info.java");
        return Files.exists(packageInfo)
                && Files.readString(packageInfo, StandardCharsets.UTF_8).contains("@Requires(classes");
    }

    private static Set<String> optionalApisIn(String code) {
        Set<String> touched = new TreeSet<>();
        OPTIONAL_PACKAGES.forEach((artifact, prefixes) -> {
            if (prefixes.stream().anyMatch(code::contains)) {
                touched.add(artifact);
            }
        });
        return touched;
    }

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> paths = Files.walk(SOURCES)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("package-info.java"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Strips comments and string literals, leaving only what actually causes a class to be loaded.
     *
     * <p>String literals matter as much as comments here: naming an optional class as text and probing it
     * with {@code Class.forName} is precisely the safe-when-absent idiom this rule exists to permit — it is
     * what lets {@code KafkaController}, {@code MicronautPanelAvailability} and {@code BootUiEngineFactory}
     * describe an integration they must not link against. Only a type reference — an import, or a
     * fully-qualified name in code — loads the class.
     */
    private static String withoutCommentsAndStringLiterals(String source) {
        String withoutComments = LINE_COMMENT
                .matcher(BLOCK_COMMENT.matcher(source).replaceAll(""))
                .replaceAll("");
        return STRING_LITERAL.matcher(withoutComments).replaceAll("\"\"");
    }
}
