package org.learningfromexperience.bootfixture.orders;

import java.util.Set;
import org.springframework.stereotype.Repository;

@Repository
class OrderRepository {
    private final Set<Integer> discountPercents = Set.of(0, 10, 20);

    int findDiscount(int requestedPercent) {
        if (!discountPercents.contains(requestedPercent)) {
            throw new IllegalArgumentException("Unknown discount");
        }
        return requestedPercent;
    }
}
