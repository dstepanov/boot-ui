package io.github.jdubois.bootui.sample.catalog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SampleNestedTransactionStep {

    private final ProductRepository products;

    public SampleNestedTransactionStep(ProductRepository products) {
        this.products = products;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public long run() {
        return products.count();
    }
}
