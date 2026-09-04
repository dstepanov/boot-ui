package io.github.jdubois.bootui.micronaut.vulnerabilities;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DependencyDto;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the reason the provider does not trust {@code java.class.path} alone.
 *
 * <p>An in-process launcher — Maven's {@code exec:java} above all, which is how a Micronaut sample is
 * commonly started — runs the application inside the build tool's own JVM. {@code java.class.path} then
 * describes the launcher, not the application, and the inventory came back with a single wrong entry
 * ({@code org.apache.maven.wrapper:maven-wrapper}) instead of the application's dependencies. These tests
 * reproduce that shape exactly: a jar that is reachable only through a classloader, never through
 * {@code java.class.path}, and which must still appear in the inventory.
 */
class MicronautDependencyProviderTest {

    @Test
    void findsADependencyReachableOnlyThroughTheClassLoader(@TempDir Path directory) throws Exception {
        Path jar = jarWithPomProperties(directory.resolve("acme-widgets-2.1.0.jar"), "com.acme", "widgets", "2.1.0");
        assertThat(System.getProperty("java.class.path", "")).doesNotContain(jar.toString());

        List<DependencyDto> dependencies = scan(jar);

        assertThat(dependencies)
                .anySatisfy(dependency -> assertThat(dependency)
                        .extracting(
                                DependencyDto::groupId,
                                DependencyDto::artifactId,
                                DependencyDto::version,
                                DependencyDto::packageName,
                                DependencyDto::source)
                        .containsExactly(
                                "com.acme",
                                "widgets",
                                "2.1.0",
                                "com.acme:widgets",
                                MicronautDependencyProvider.SOURCE));
    }

    @Test
    void reportsEachCoordinateOnceEvenWhenSeveralSourcesSeeTheSameJar(@TempDir Path directory) throws Exception {
        Path jar = jarWithPomProperties(directory.resolve("acme-widgets-2.1.0.jar"), "com.acme", "widgets", "2.1.0");

        // The same jar twice on the same loader is the cheap stand-in for the real overlap: a jar that both
        // java.class.path and the classloader report.
        List<DependencyDto> dependencies = scan(jar, jar);

        assertThat(dependencies)
                .filteredOn(dependency -> "com.acme:widgets".equals(dependency.packageName()))
                .hasSize(1);
    }

    @Test
    void fallsBackToTheBundledPomWhenThereIsNoPomProperties(@TempDir Path directory) throws Exception {
        // Exactly Micronaut's own packaging: published from Gradle, so META-INF/maven carries the pom.xml
        // and no pom.properties. A properties-only reader omits the framework the application runs on.
        Path jar = directory.resolve("micronaut-core-4.10.26.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/maven/io.micronaut/micronaut-core/pom.xml"));
            out.write("""
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>io.micronaut</groupId>
                      <artifactId>micronaut-core</artifactId>
                      <version>4.10.26</version>
                    </project>
                    """.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        assertThat(scan(jar))
                .anySatisfy(dependency -> assertThat(dependency)
                        .extracting(DependencyDto::packageName, DependencyDto::version)
                        .containsExactly("io.micronaut:micronaut-core", "4.10.26"));
    }

    @Test
    void skipsABundledPomWhoseVersionIsAnUnresolvedPlaceholder(@TempDir Path directory) throws Exception {
        Path jar = directory.resolve("templated-1.0.0.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/maven/com.acme/templated/pom.xml"));
            out.write("""
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <groupId>com.acme</groupId>
                      <artifactId>templated</artifactId>
                      <version>${revision}</version>
                    </project>
                    """.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        assertThat(scan(jar))
                .noneSatisfy(dependency -> assertThat(dependency.packageName()).isEqualTo("com.acme:templated"));
    }

    @Test
    void prefersPomPropertiesOverTheBundledPomForTheSameArtifact(@TempDir Path directory) throws Exception {
        Path jar = directory.resolve("acme-widgets-2.1.0.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/maven/com.acme/widgets/pom.properties"));
            out.write("groupId=com.acme\nartifactId=widgets\nversion=2.1.0\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/maven/com.acme/widgets/pom.xml"));
            out.write("""
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <groupId>com.acme</groupId>
                      <artifactId>widgets</artifactId>
                      <version>2.1.0</version>
                    </project>
                    """.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        assertThat(scan(jar))
                .filteredOn(dependency -> "com.acme:widgets".equals(dependency.packageName()))
                .hasSize(1);
    }

    @Test
    void skipsAJarWhoseMavenMetadataIsIncomplete(@TempDir Path directory) throws Exception {
        Path jar = directory.resolve("nameless-1.0.0.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/maven/com.acme/nameless/pom.properties"));
            // No version: a guessed coordinate would produce wrong vulnerability findings, so it is dropped.
            out.write("groupId=com.acme\nartifactId=nameless\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        assertThat(scan(jar))
                .noneSatisfy(dependency -> assertThat(dependency.packageName()).isEqualTo("com.acme:nameless"));
    }

    /** Runs the provider over a classloader that sees exactly the given jars, and nothing else new. */
    private static List<DependencyDto> scan(Path... jars) throws Exception {
        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) {
            urls[i] = jars[i].toUri().toURL();
        }
        try (URLClassLoader classLoader = new URLClassLoader(urls, null)) {
            return new MicronautDependencyProvider(classLoader).dependencies();
        }
    }

    private static Path jarWithPomProperties(Path jar, String groupId, String artifactId, String version)
            throws Exception {
        try (OutputStream file = Files.newOutputStream(jar);
                JarOutputStream out = new JarOutputStream(file)) {
            out.putNextEntry(new JarEntry("META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties"));
            String properties = "groupId=" + groupId + "\nartifactId=" + artifactId + "\nversion=" + version + "\n";
            out.write(properties.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
