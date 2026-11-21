package org.learningfromexperience.bootfixture.orders;

import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = OrdersApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderApiIntegrationTest {
    @Autowired
    private TestRestTemplate client;

    @Test
    void rejectsInvalidQuantity() {
        var request = new OrderController.QuoteRequest(0, new BigDecimal("60"), 10);
        var result = client.postForEntity("/orders/quote", request, OrderController.ErrorResponse.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody().error()).isEqualTo("Quantity must be positive");
    }

    @Test
    @Tag("debug-defect")
    void quoteAppliesDiscount() {
        var request = new OrderController.QuoteRequest(2, new BigDecimal("60"), 10);
        var result = client.postForEntity("/orders/quote", request, OrderController.QuoteResponse.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().total()).isEqualByComparingTo("108.00");
    }
}
