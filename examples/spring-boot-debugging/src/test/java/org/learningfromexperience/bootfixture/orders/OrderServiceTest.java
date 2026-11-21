package org.learningfromexperience.bootfixture.orders;

import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OrderServiceTest {
    private final OrderService service = new OrderService(new OrderRepository());

    @Test
    void quotesFullPrice() {
        assertThat(service.quote(2, new BigDecimal("60"), 0)).isEqualByComparingTo("120.00");
    }

    @Test
    @Tag("debug-defect")
    void calculatesDiscount() {
        assertThat(service.quote(2, new BigDecimal("60"), 10)).isEqualByComparingTo("108.00");
    }
}
