package io.github.jdubois.bootui.micronaut.vulnerabilities;

import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyProvider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Builds the application's dependency inventory by reading the Maven coordinates each jar carries.
 *
 * <p>This is the Micronaut analogue of the Quarkus adapter's build-time application-model capture and the
 * Spring adapter's build-info reader, but it needs neither: every jar published to Maven Central embeds
 * {@code META-INF/maven/<group>/<artifact>/pom.properties}, so the <em>actual runtime classpath</em> is the
 * inventory. That is arguably the most honest source available — it describes what the JVM really loaded,
 * including anything a build tool resolved transitively.
 *
 * <p>The scan is bounded and lazy: it runs once, on first use, and skips anything it cannot read. A jar
 * without Maven metadata (a shaded uber-jar, a directory on the classpath) contributes nothing rather than a
 * guessed coordinate — a wrong coordinate would produce wrong vulnerability findings, which is worse than a
 * missing one.
 */
public final class MicronautDependencyProvider implements DependencyProvider {

    static final String SOURCE = "runtime classpath";

    private static final String MAVEN_PREFIX = "META-INF/maven/";

    private static final String POM_PROPERTIES = "pom.properties";

    private volatile List<DependencyDto> cached;

    @Override
    public List<DependencyDto> dependencies() {
        List<DependencyDto> snapshot = cached;
        if (snapshot == null) {
            snapshot = scanClasspath();
            cached = snapshot;
        }
        return snapshot;
    }

    private static List<DependencyDto> scanClasspath() {
        Map<String, DependencyDto> dependencies = new LinkedHashMap<>();
        String classpath = System.getProperty("java.class.path", "");
        for (String entry : classpath.split(File.pathSeparator)) {
            if (entry == null || entry.isBlank() || !entry.endsWith(".jar")) {
                continue;
            }
            readCoordinates(entry, dependencies);
        }
        return dependencies.values().stream()
                .sorted(Comparator.comparing(DependencyDto::packageName).thenComparing(DependencyDto::version))
                .toList();
    }

    /**
     * Reads the Maven coordinate from one jar. A jar can legitimately contain several (a shaded artifact),
     * so every {@code pom.properties} it carries is read.
     */
    private static void readCoordinates(String jarPath, Map<String, DependencyDto> dependencies) {
        try (ZipFile jar = new ZipFile(jarPath)) {
            Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(MAVEN_PREFIX) || !name.endsWith(POM_PROPERTIES)) {
                    continue;
                }
                DependencyDto dependency = readPomProperties(jar, entry);
                if (dependency != null) {
                    dependencies.putIfAbsent(dependency.packageName() + ":" + dependency.version(), dependency);
                }
            }
        } catch (IOException | RuntimeException ex) {
            // A jar that cannot be opened contributes nothing; the inventory must never fail the panel.
        }
    }

    private static DependencyDto readPomProperties(ZipFile jar, ZipEntry entry) {
        try (InputStream in = jar.getInputStream(entry)) {
            Properties properties = new Properties();
            properties.load(in);
            String groupId = trimmed(properties.getProperty("groupId"));
            String artifactId = trimmed(properties.getProperty("artifactId"));
            String version = trimmed(properties.getProperty("version"));
            if (groupId == null || artifactId == null || version == null) {
                return null;
            }
            return new DependencyDto(
                    groupId, artifactId, version, groupId + ":" + artifactId, SOURCE, 0, "NONE", List.of());
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
