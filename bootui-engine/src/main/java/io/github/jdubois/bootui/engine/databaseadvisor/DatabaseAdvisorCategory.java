package io.github.jdubois.bootui.engine.databaseadvisor;

enum DatabaseAdvisorCategory {
    SCHEMA("Schema"),
    HIBERNATE_MAPPING("Hibernate mapping"),
    RUNTIME_SQL("Runtime SQL");

    private final String label;

    DatabaseAdvisorCategory(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}
