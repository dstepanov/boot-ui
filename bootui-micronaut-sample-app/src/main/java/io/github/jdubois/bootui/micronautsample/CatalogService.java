package io.github.jdubois.bootui.micronautsample;

import jakarta.inject.Singleton;
import java.util.List;

/** An application bean the console's Beans panel shows, injected by {@link CatalogController}. */
@Singleton
public class CatalogService {

    private static final List<String> TITLES =
            List.of("The Pragmatic Programmer", "Refactoring", "Release It!", "Designing Data-Intensive Applications");

    public List<String> titles() {
        return TITLES;
    }

    public String title(int index) {
        if (index < 0 || index >= TITLES.size()) {
            throw new IllegalArgumentException("No catalog entry at index " + index);
        }
        return TITLES.get(index);
    }
}
