package io.github.jdubois.bootui.sample.catalog;

import jakarta.persistence.*;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;

@Entity
@Table(name = "sample_products")
@Cacheable
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
// Hibernate Advisor demo: the IDENTITY id below intentionally triggers HIB-ID-001.
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String category;

    private boolean active;

    protected Product() {}

    public Product(String name, String category, boolean active) {
        this.name = name;
        this.category = category;
        this.active = active;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public boolean isActive() {
        return active;
    }
}
