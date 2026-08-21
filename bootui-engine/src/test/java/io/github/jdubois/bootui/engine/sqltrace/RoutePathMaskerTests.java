package io.github.jdubois.bootui.engine.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RoutePathMaskerTests {

    @Test
    void keepsFixedRouteWords() {
        assertThat(RoutePathMasker.mask("/api/orders")).isEqualTo("/api/orders");
        assertThat(RoutePathMasker.mask("/api/order-items/summary")).isEqualTo("/api/order-items/summary");
    }

    @Test
    void masksNumericIdentifiers() {
        assertThat(RoutePathMasker.mask("/api/orders/4711")).isEqualTo("/api/orders/{value}");
    }

    @Test
    void masksUuidsAndHexTokens() {
        assertThat(RoutePathMasker.mask("/api/orders/3f2504e0-4f89-11d3-9a0c-0305e82c3301"))
                .isEqualTo("/api/orders/{value}");
        assertThat(RoutePathMasker.mask("/files/9f3ba1c2d4e5")).isEqualTo("/files/{value}");
    }

    @Test
    void masksEmailAddressesAndEncodedSegments() {
        assertThat(RoutePathMasker.mask("/users/ada@example.com")).isEqualTo("/users/{value}");
        assertThat(RoutePathMasker.mask("/search/ada%20lovelace")).isEqualTo("/search/{value}");
    }

    @Test
    void masksLongSegmentsThatCannotBeRouteWords() {
        assertThat(RoutePathMasker.mask("/t/" + "a".repeat(60))).isEqualTo("/t/{value}");
    }

    @Test
    void masksClientSideNullsSoOneRouteDoesNotSplitIntoThree() {
        assertThat(RoutePathMasker.mask("/api/orders/null")).isEqualTo("/api/orders/{value}");
        assertThat(RoutePathMasker.mask("/api/orders/undefined")).isEqualTo("/api/orders/{value}");
    }

    @Test
    void keepsStaticResourceNamesWithASingleExtension() {
        assertThat(RoutePathMasker.mask("/assets/app.css")).isEqualTo("/assets/app.css");
        assertThat(RoutePathMasker.mask("/assets/app.a1b2c3.css")).isEqualTo("/assets/{value}");
    }

    @Test
    void passesTemplateSegmentsThroughUnchanged() {
        assertThat(RoutePathMasker.mask("/api/orders/{id}/lines")).isEqualTo("/api/orders/{id}/lines");
    }

    @Test
    void neverKeepsAQueryStringOrFragment() {
        assertThat(RoutePathMasker.mask("/api/orders?status=OPEN&token=secret")).isEqualTo("/api/orders");
        assertThat(RoutePathMasker.mask("/api/orders#anchor")).isEqualTo("/api/orders");
    }

    @Test
    void truncatesPathologicallyDeepPaths() {
        String deep = "/a".repeat(40);

        String masked = RoutePathMasker.mask(deep);

        assertThat(masked).endsWith("/…");
        assertThat(masked.split("/").length).isLessThan(20);
    }

    @Test
    void normalizesEmptyAndRootPaths() {
        assertThat(RoutePathMasker.mask(null)).isEqualTo("/");
        assertThat(RoutePathMasker.mask("")).isEqualTo("/");
        assertThat(RoutePathMasker.mask("/")).isEqualTo("/");
        assertThat(RoutePathMasker.mask("?a=b")).isEqualTo("/");
    }
}
