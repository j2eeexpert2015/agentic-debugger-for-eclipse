package org.learningfromexperience.bootfixture.orders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

@Service
class OrderService {
    private final OrderRepository repository;

    OrderService(OrderRepository repository) {
        this.repository = repository;
    }

    BigDecimal quote(int quantity, BigDecimal unitPrice, int discountPercent) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new IllegalArgumentException("Unit price must be nonnegative");
        }
        int percent = repository.findDiscount(discountPercent);
        int rate = percent / 100;
        BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
        return subtotal.multiply(BigDecimal.valueOf(1 - rate)).setScale(2, RoundingMode.HALF_UP);
    }
}
