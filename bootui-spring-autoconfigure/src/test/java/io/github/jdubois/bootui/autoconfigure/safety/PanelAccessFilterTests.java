package io.github.jdubois.bootui.autoconfigure.safety;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.conformance.BootUiApiContractCatalog;
import io.github.jdubois.bootui.conformance.BootUiApiContractCatalog.ActionContract;
import io.github.jdubois.bootui.conformance.BootUiApiContractCatalog.Runtime;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PanelAccessFilterTests {

    private BootUiProperties properties;

    private PanelAccessFilter filter;

    @BeforeEach
    void setUp() {
        properties = new BootUiProperties();
        filter = new PanelAccessFilter(properties);
    }

    @Test
    void allowsEnabledPanelReadRequest() throws Exception {
        MockHttpServletRequest request = request("GET", "/bootui/api/config");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void blocksDisabledPanelReadRequest() throws Exception {
        properties.panel("config").setEnabled(false);
        MockHttpServletRequest request = request("GET", "/bootui/api/config");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString())
                .contains("\"panel\":\"config\"")
                .contains("bootui.panels.config.enabled=false");
    }

    @Test
    void blocksDisabledPanelActionRequest() throws Exception {
        properties.panel("loggers").setEnabled(false);
        MockHttpServletRequest request = request("POST", "/bootui/api/loggers/io.github.jdubois.bootui");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"panel\":\"loggers\"");
    }

    @Test
    void allowsReadOnlyPanelReadRequest() throws Exception {
        properties.panel("config").setReadOnly(true);
        MockHttpServletRequest request = request("GET", "/bootui/api/config");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void blocksReadOnlyPanelActionRequest() throws Exception {
        properties.panel("config").setReadOnly(true);
        MockHttpServletRequest request = request("POST", "/bootui/api/config/overrides");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("\"panel\":\"config\"")
                .contains("bootui.panels.config.read-only=true");
    }

    @Test
    void globalReadOnlyBlocksActionCapablePanelActions() throws Exception {
        properties.setReadOnly(true);
        List<ActionContract> actions = BootUiApiContractCatalog.actions(Runtime.SPRING_MVC).stream()
                .filter(action -> action.panelId() != null)
                .toList();

        assertThat(actions.stream().map(ActionContract::panelId).collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(BootUiPanels.all().stream()
                        .filter(BootUiPanels.Panel::actionCapable)
                        .map(BootUiPanels.Panel::id)
                        .toList());
        for (ActionContract action : actions) {
            MockHttpServletRequest request = request(action.method(), "/bootui/api" + action.relativePath());
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getStatus()).as(action.id()).isEqualTo(403);
            assertThat(response.getContentAsString())
                    .as(action.id())
                    .contains("\"panel\":\"" + action.panelId() + "\"")
                    .contains("bootui.read-only=true");
        }
    }

    @Test
    void perPanelReadOnlyBlocksPentestingScanAction() throws Exception {
        properties.panel("pentesting").setReadOnly(true);
        MockHttpServletRequest request = request("POST", "/bootui/api/pentesting/scan");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("\"panel\":\"pentesting\"")
                .contains("bootui.panels.pentesting.read-only=true");
    }

    @Test
    void perPanelReadOnlyAllowsPentestingReportRead() throws Exception {
        properties.panel("pentesting").setReadOnly(true);
        MockHttpServletRequest request = request("GET", "/bootui/api/pentesting");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void globalReadOnlyDoesNotBlockReadOnlyPanelWithoutActions() throws Exception {
        properties.setReadOnly(true);
        MockHttpServletRequest request = request("GET", "/bootui/api/metrics");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void overviewShellEndpointIsNeverGatedByPanelToggle() throws Exception {
        // GET /bootui/api/overview is the shell's framework-neutral chrome data source (and CSRF-cookie
        // primer), so disabling the Overview dashboard panel must not 403 it — otherwise the whole console,
        // and every state-changing action that needs the CSRF token, would break. The panel id still gates
        // the MCP get_overview tool; only this path-based gating is intentionally bypassed.
        properties.panel(BootUiPanels.OVERVIEW).setEnabled(false);
        MockHttpServletRequest request = request("GET", "/bootui/api/overview");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void skipsCorePanelMetadataEndpoint() throws Exception {
        properties.setReadOnly(true);
        MockHttpServletRequest request = request("GET", "/bootui/api/panels");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void skipsOtlpIngestionEndpoint() throws Exception {
        properties.setReadOnly(true);
        MockHttpServletRequest request = request("POST", "/bootui/api/otlp/v1/traces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void honorsCustomApiPathAndContextPath() throws Exception {
        properties.setApiPath("/admin/api");
        properties.panel("cache").setEnabled(false);
        MockHttpServletRequest request = request("POST", "/my-app/admin/api/cache/clear");
        request.setContextPath("/my-app");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"panel\":\"cache\"");
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }
}
