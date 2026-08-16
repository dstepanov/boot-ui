package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.engine.panel.BootUiGlobalWritePolicy;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.panel.BootUiPanels.Panel;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class BackendPanelCatalogConsistencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PANEL_ACCESS_ROW = Pattern.compile(
            "^\\|\\s*[^|]+\\|\\s*([^|]+?)\\s*\\|\\s*`([^`]+)`\\s*\\|\\s*`([^`]+)`\\s*\\|\\s*(.*?)\\s*\\|$",
            Pattern.MULTILINE);

    private static final List<ManifestResource> MANIFEST_RESOURCES = List.of(
            new ManifestResource("/io/github/jdubois/bootui/conformance/expected-panels-spring.json", "spring-boot"),
            new ManifestResource("/io/github/jdubois/bootui/conformance/expected-panels-quarkus.json", "quarkus"),
            new ManifestResource(
                    "/io/github/jdubois/bootui/conformance/expected-panels-webflux.json", "spring-boot-reactive"));

    @Test
    void backendCatalogMatchesConformanceManifestsExactly() {
        List<PanelMetadata> expected = BootUiPanels.all().stream()
                .map(panel -> new PanelMetadata(panel.id(), panel.title(), panel.actionCapable()))
                .toList();

        for (ManifestResource resource : MANIFEST_RESOURCES) {
            JsonNode manifest = loadJsonResource(resource.path());
            assertThat(manifest.path("platform").asText())
                    .as("%s platform", resource.path())
                    .isEqualTo(resource.platform());
            assertThat(manifest.path("panels")).as("%s panels", resource.path()).isNotNull();
            assertThat(manifest.path("panels").isArray())
                    .as("%s panels array", resource.path())
                    .isTrue();

            List<PanelMetadata> actual = new java.util.ArrayList<>();
            for (JsonNode node : manifest.path("panels")) {
                assertThat(node.path("id").isTextual())
                        .as("%s panel id", resource.path())
                        .isTrue();
                assertThat(node.path("title").isTextual())
                        .as("%s panel title", resource.path())
                        .isTrue();
                assertThat(node.path("actionCapable").isBoolean())
                        .as("%s panel actionCapable", resource.path())
                        .isTrue();
                actual.add(new PanelMetadata(
                        node.path("id").asText(),
                        node.path("title").asText(),
                        node.path("actionCapable").asBoolean()));
            }
            assertThat(actual)
                    .as("%s panel metadata and contractual order", resource.path())
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    void backendCatalogIsDocumentedInFeaturesGuide() {
        String features = readFeaturesMarkdown();

        for (Panel panel : BootUiPanels.all()) {
            String headingPrefix = panel.title().equals("Overview") ? "## " : "### ";
            assertThat(features)
                    .as("docs/FEATURES.md heading for panel '%s'", panel.title())
                    .containsPattern(Pattern.compile(
                            "^" + Pattern.quote(headingPrefix + panel.title()) + "$", Pattern.MULTILINE));
        }
    }

    @Test
    void backendCatalogMatchesPropertiesPanelAccessMatrix() {
        String properties = readPropertiesMarkdown();
        String accessMatrix = properties.substring(
                properties.indexOf("## Panel access settings"), properties.indexOf("## Per-panel action details"));
        Matcher rows = PANEL_ACCESS_ROW.matcher(accessMatrix);
        List<PanelAccessMetadata> documented = new ArrayList<>();
        while (rows.find()) {
            documented.add(new PanelAccessMetadata(
                    rows.group(1).trim(), rows.group(2), rows.group(3), markdownCodeValue(rows.group(4))));
        }

        List<PanelAccessMetadata> expected = BootUiPanels.all().stream()
                .map(panel -> new PanelAccessMetadata(
                        panel.title(),
                        panel.id(),
                        "bootui.panels." + panel.id() + ".enabled",
                        panel.actionCapable()
                                ? "bootui.panels." + panel.id() + ".read-only"
                                : "Not applicable; view-only."))
                .toList();

        assertThat(documented).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void readContractCatalogCoversEveryDataPanelExactlyOnce() {
        Set<String> expected = BootUiPanels.all().stream()
                .map(Panel::id)
                .filter(id -> !BootUiPanels.HTTP_PROBE.equals(id))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(BootUiApiContractCatalog.reads())
                .extracting(BootUiApiContractCatalog.ReadContract::panelId)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void actionContractCatalogIsUniqueAndResolvesThroughThePanelRegistry() {
        List<BootUiApiContractCatalog.ActionContract> actions = BootUiApiContractCatalog.actions();

        assertThat(actions)
                .extracting(action -> action.method() + " " + action.relativePath() + " " + action.runtimes())
                .doesNotHaveDuplicates();
        assertThat(actions).allSatisfy(action -> {
            assertThat(action.relativePath()).startsWith("/");
            assertThat(action.method()).isIn("POST", "PUT", "PATCH", "DELETE");
            assertThat(action.runtimes()).isNotEmpty();
            if (action.panelId() != null) {
                assertThat(BootUiPanels.byApiPath(action.relativePath()))
                        .as(action.id())
                        .get()
                        .extracting(Panel::id)
                        .isEqualTo(action.panelId());
            }
        });

        Set<String> expectedActionPanels = BootUiPanels.all().stream()
                .filter(Panel::actionCapable)
                .map(Panel::id)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(actions.stream()
                        .map(BootUiApiContractCatalog.ActionContract::panelId)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(expectedActionPanels);

        assertThat(actions)
                .filteredOn(action -> action.panelId() == null && action.blockedByGlobalReadOnly())
                .allSatisfy(action -> assertThat(BootUiGlobalWritePolicy.subjectFor(action.relativePath()))
                        .as(action.id())
                        .isPresent());
    }

    private static JsonNode loadJsonResource(String resource) {
        try (InputStream stream = BackendPanelCatalogConsistencyTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing resource: " + resource);
            }
            return MAPPER.readTree(stream);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read resource: " + resource, ex);
        }
    }

    private static String readFeaturesMarkdown() {
        return readDocumentation("FEATURES.md");
    }

    private static String readPropertiesMarkdown() {
        return readDocumentation("PROPERTIES.md");
    }

    private static String readDocumentation(String filename) {
        Path root = findRepositoryRoot(Path.of(".").toAbsolutePath());
        Path documentation = root.resolve("docs").resolve(filename);
        try {
            return Files.readString(documentation);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read " + documentation, ex);
        }
    }

    private static String markdownCodeValue(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("`") && trimmed.endsWith("`")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static Path findRepositoryRoot(Path start) {
        Path cursor = start;
        while (cursor != null) {
            if (Files.isDirectory(cursor.resolve("bootui-engine"))
                    && Files.isDirectory(cursor.resolve("bootui-ui"))
                    && Files.exists(cursor.resolve("pom.xml"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("Unable to locate repository root from " + start);
    }

    private record ManifestResource(String path, String platform) {}

    private record PanelMetadata(String id, String title, boolean actionCapable) {}

    private record PanelAccessMetadata(String title, String id, String enabledProperty, String readOnlyProperty) {}
}
