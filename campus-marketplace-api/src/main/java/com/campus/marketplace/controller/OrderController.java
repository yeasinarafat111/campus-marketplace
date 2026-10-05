package com.campus.marketplace.controller;

import com.campus.marketplace.dto.OrderResponse;
import com.campus.marketplace.dto.PageResponse;
import com.campus.marketplace.dto.ReportProblemRequest;
import com.campus.marketplace.dto.RatingRequest;
import com.campus.marketplace.service.OrderService;
import com.campus.marketplace.service.RatingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own orders. Needs a login (SecurityConfig); the service only ever returns the caller's. */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final RatingService ratingService;

    @GetMapping("/purchases")
    public PageResponse<OrderResponse> purchases(@RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return orderService.purchases(page, size);
    }

    /** Buyer: "I received it". Completes the order. */
    @PostMapping("/{id}/confirm")
    public OrderResponse confirm(@PathVariable Long id) {
        return orderService.confirmReceipt(id);
    }

    /** Buyer: report a problem. Freezes the order until an admin resolves it. */
    @PostMapping("/{id}/report")
    public OrderResponse report(@PathVariable Long id, @Valid @RequestBody ReportProblemRequest request) {
        return orderService.reportProblem(id, request.reason());
    }

    /** Buyer: rate the seller 1-5 (and optionally comment) once the order is complete. One rating per order. */
    @PostMapping("/{id}/rating")
    public OrderResponse rate(@PathVariable Long id, @Valid @RequestBody RatingRequest request) {
        return ratingService.rate(id, request);
    }

    @GetMapping("/sales")
    public PageResponse<OrderResponse> sales(@RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return orderService.sales(page, size);
    }
}
