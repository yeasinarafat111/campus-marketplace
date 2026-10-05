package com.campus.marketplace.controller;

import com.campus.marketplace.dto.OrderResponse;
import com.campus.marketplace.dto.PageResponse;
import com.campus.marketplace.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own orders. Needs a login (SecurityConfig); the service only ever returns the caller's. */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping("/purchases")
    public PageResponse<OrderResponse> purchases(@RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return orderService.purchases(page, size);
    }

    @GetMapping("/sales")
    public PageResponse<OrderResponse> sales(@RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return orderService.sales(page, size);
    }
}
