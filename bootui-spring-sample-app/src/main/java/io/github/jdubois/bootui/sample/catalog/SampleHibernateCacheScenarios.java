package io.github.jdubois.bootui.sample.catalog;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.stereotype.Service;

@Service
public class SampleHibernateCacheScenarios {

    private final ProductRepository products;
    private final EntityManagerFactory entityManagerFactory;

    public SampleHibernateCacheScenarios(ProductRepository products, EntityManagerFactory entityManagerFactory) {
        this.products = products;
        this.entityManagerFactory = entityManagerFactory;
    }

    public synchronized Map<String, Object> run() {
        Long productId = products.findAll().stream()
                .findFirst()
                .map(Product::getId)
                .orElseThrow(() -> new IllegalStateException("No sample product is available"));

        entityManagerFactory.getCache().evict(Product.class);
        Statistics statistics =
                entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long hitsBefore = statistics.getSecondLevelCacheHitCount();
        long missesBefore = statistics.getSecondLevelCacheMissCount();
        long putsBefore = statistics.getSecondLevelCachePutCount();

        ProductSummary firstLoad = loadInNewPersistenceContext(productId);
        ProductSummary secondLoad = loadInNewPersistenceContext(productId);

        return Map.of(
                "product",
                secondLoad,
                "loads",
                2,
                "firstLoadProduct",
                firstLoad.name(),
                "hitCountDelta",
                statistics.getSecondLevelCacheHitCount() - hitsBefore,
                "missCountDelta",
                statistics.getSecondLevelCacheMissCount() - missesBefore,
                "putCountDelta",
                statistics.getSecondLevelCachePutCount() - putsBefore,
                "regionName",
                Product.class.getName());
    }

    private ProductSummary loadInNewPersistenceContext(Long productId) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        EntityTransaction transaction = entityManager.getTransaction();
        try {
            transaction.begin();
            Product product = entityManager.find(Product.class, productId);
            if (product == null) {
                throw new IllegalStateException("Sample product " + productId + " was not found");
            }
            ProductSummary summary = ProductSummary.from(product);
            transaction.commit();
            return summary;
        } catch (RuntimeException failure) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            throw failure;
        } finally {
            entityManager.close();
        }
    }
}
