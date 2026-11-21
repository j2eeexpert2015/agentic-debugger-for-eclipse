package org.learningfromexperience.bootfixture.orders;

import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
class OrderController {
    record QuoteRequest(int quantity, BigDecimal unitPrice, int discountPercent) {}
    record QuoteResponse(BigDecimal total) {}
    record ErrorResponse(String error) {}

    private final OrderService service;

    OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping("/orders/quote")
    QuoteResponse quote(@RequestBody QuoteRequest request) {
        return new QuoteResponse(service.quote(request.quantity(), request.unitPrice(), request.discountPercent()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponse invalidOrder(IllegalArgumentException error) {
        return new ErrorResponse(error.getMessage());
    }
}
