package io.github.jdubois.bootui.inventorytest;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Head;

/**
 * A stand-in application controller with both shapes of HEAD route the Mappings panel has to tell apart.
 *
 * <p>{@link #list()} is an ordinary {@code @Get}, so Micronaut also registers a generated {@code HEAD}
 * route for it that no one declared. {@link #headOnly()} is an explicitly declared {@code @Head}, which is
 * a declaration like any other and must survive.
 */
@Controller(InventoryTestController.PATH)
@Requires(property = InventoryTestFixtures.PROPERTY, value = StringUtils.TRUE)
public class InventoryTestController {

    public static final String PATH = "/inventory-test";

    /** Declares GET; Micronaut generates the HEAD counterpart the panel must not list. */
    @Get
    public String list() {
        return "ok";
    }

    /** Declares HEAD itself, so the panel must list it. */
    @Head("/head-only")
    public HttpResponse<?> headOnly() {
        return HttpResponse.ok();
    }
}
