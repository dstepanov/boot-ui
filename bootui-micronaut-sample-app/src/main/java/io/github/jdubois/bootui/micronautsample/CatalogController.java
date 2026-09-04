package io.github.jdubois.bootui.micronautsample;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import java.util.List;

/**
 * A small application endpoint, so the sample has real routes, beans and traffic for the console's
 * Mappings, Beans and HTTP panels to describe.
 */
@Controller("/catalog")
public class CatalogController {

    private final CatalogService catalog;
    private final FlakyService flaky;

    public CatalogController(CatalogService catalog, FlakyService flaky) {
        this.catalog = catalog;
        this.flaky = flaky;
    }

    @Get
    public List<String> list() {
        return catalog.titles();
    }

    @Get("/{index}")
    public String title(@PathVariable int index) {
        return catalog.title(index);
    }

    /** Exercises the retry policy, so the Fault Tolerance panel has events to show. */
    @Get("/flaky")
    public String flaky() {
        return flaky.flaky();
    }
}
