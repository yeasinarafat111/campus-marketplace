package com.campus.marketplace.service;

import com.campus.marketplace.dto.AdminOrderResponse;
import com.campus.marketplace.dto.OrderResponse;
import com.campus.marketplace.dto.PageResponse;
import com.campus.marketplace.entity.Order;
import com.campus.marketplace.entity.OrderStatus;
import com.campus.marketplace.entity.PayoutStatus;
import com.campus.marketplace.exception.BadRequestException;
import com.campus.marketplace.exception.ConflictException;
import com.campus.marketplace.exception.ResourceNotFoundException;
import com.campus.marketplace.repository.OrderRepository;
import com.campus.marketplace.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final CurrentUserProvider currentUser;

    /** The caller's own purchases, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> purchases(int page, int size) {
        return PageResponse.of(orderRepository.findByBuyerId(currentUser.requireId(), pageable(page, size)),
                OrderResponse::forBuyer);
    }

    /** The caller's own sales, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> sales(int page, int size) {
        return PageResponse.of(orderRepository.findBySellerId(currentUser.requireId(), pageable(page, size)),
                OrderResponse::forSeller);
    }

    /** Admin: every order, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<AdminOrderResponse> all(int page, int size) {
        return PageResponse.of(orderRepository.findAll(pageable(page, size)), AdminOrderResponse::from);
    }

    /**
     * Admin: records that the seller has been paid out (outside this system, until Stripe Connect). Completes
     * the order. Idempotent: marking an already-paid-out order returns it unchanged.
     */
    @Transactional
    public AdminOrderResponse markPaidOut(Long orderId) {
        Order order = orderRepository.findForUpdateById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " not found"));
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new ConflictException("A cancelled order can't be paid out");
        }
        if (order.getPayoutStatus() != PayoutStatus.PAID_OUT) {
            order.setPayoutStatus(PayoutStatus.PAID_OUT);
            order.setStatus(OrderStatus.COMPLETED);
            order.setCompletedAt(Instant.now());
            log.info("Admin {} marked order {} as paid out", currentUser.requireId(), orderId);
        }
        return AdminOrderResponse.from(order);
    }

    private static Pageable pageable(int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must be 0 or greater");
        }
        if (size < 1 || size > ListingService.MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + ListingService.MAX_PAGE_SIZE);
        }
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
    }
}
