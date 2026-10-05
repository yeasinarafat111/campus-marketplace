package com.campus.marketplace.service;

import com.campus.marketplace.dto.AdminOrderResponse;
import com.campus.marketplace.dto.OrderResponse;
import com.campus.marketplace.dto.PageResponse;
import com.campus.marketplace.entity.Order;
import com.campus.marketplace.entity.OrderStatus;
import com.campus.marketplace.entity.PayoutStatus;
import com.campus.marketplace.exception.BadRequestException;
import com.campus.marketplace.exception.ConflictException;
import com.campus.marketplace.exception.ForbiddenException;
import com.campus.marketplace.exception.ResourceNotFoundException;
import com.campus.marketplace.repository.OrderRepository;
import com.campus.marketplace.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Orders after the sale. An ONLINE order starts PAID. The buyer confirms receipt, which completes it, or it
 * completes by itself 72 hours after payment, unless the buyer reports a problem, which freezes it (no
 * auto-complete, no payout) until an admin resolves it. The seller can be paid out only for a COMPLETED order.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    /** What an admin decides about a reported order. */
    public enum Resolution { COMPLETE, CANCEL }

    private final OrderRepository orderRepository;
    private final CurrentUserProvider currentUser;
    private final com.campus.marketplace.repository.RatingRepository ratingRepository;

    /** How long after payment a PAID order completes by itself. */
    @Value("${app.orders.auto-complete-after:72h}")
    private Duration autoCompleteAfter;

    /** The caller's own purchases, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> purchases(int page, int size) {
        var orders = orderRepository.findByBuyerId(currentUser.requireId(), pageable(page, size));
        var ratings = ratingsByOrder(orders.getContent());
        return PageResponse.of(orders, o -> withRating(OrderResponse.forBuyer(o, autoCompleteAfter), ratings.get(o.getId())));
    }

    /** The caller's own sales, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> sales(int page, int size) {
        var orders = orderRepository.findBySellerId(currentUser.requireId(), pageable(page, size));
        var ratings = ratingsByOrder(orders.getContent());
        return PageResponse.of(orders, o -> withRating(OrderResponse.forSeller(o, autoCompleteAfter), ratings.get(o.getId())));
    }

    /**
     * Buyer only. "I received it": completes a PAID order. Confirming twice is a 409, and so is confirming an
     * order that has an open problem report (an admin has to resolve that first).
     */
    @Transactional
    public OrderResponse confirmReceipt(Long orderId) {
        Order order = lockOwnedByBuyer(orderId);
        if (order.getStatus() == OrderStatus.COMPLETED) {
            throw new ConflictException("You've already confirmed this order");
        }
        if (order.getStatus() != OrderStatus.PAID) {
            throw new ConflictException("This order can't be confirmed");
        }
        if (order.getProblemReportedAt() != null) {
            throw new ConflictException("This order has a problem report open. An admin is looking into it.");
        }
        order.setStatus(OrderStatus.COMPLETED);
        order.setCompletedAt(Instant.now());
        log.info("Buyer {} confirmed receipt of order {}", currentUser.requireId(), orderId);
        return OrderResponse.forBuyer(order, autoCompleteAfter);
    }

    /** Buyer only. Reports a problem with a PAID order, which freezes it. One report per order. */
    @Transactional
    public OrderResponse reportProblem(Long orderId, String reason) {
        Order order = lockOwnedByBuyer(orderId);
        if (order.getStatus() != OrderStatus.PAID) {
            throw new ConflictException("Only an order that hasn't been completed can be reported");
        }
        if (order.getProblemReportedAt() != null) {
            throw new ConflictException("You've already reported a problem with this order");
        }
        order.setProblemReportedAt(Instant.now());
        order.setProblemReason(reason);
        log.warn("Buyer {} reported a problem with order {}", currentUser.requireId(), orderId);
        return OrderResponse.forBuyer(order, autoCompleteAfter);
    }

    /** Completes every PAID, unreported order paid at least 72 hours before {@code now}. Returns how many. */
    @Transactional
    public int autoCompleteDue(Instant now) {
        int completed = orderRepository.autoCompleteDue(now.minus(autoCompleteAfter), now);
        if (completed > 0) {
            log.info("Auto-completed {} order(s) paid more than {} ago", completed, autoCompleteAfter);
        }
        return completed;
    }

    /** Admin: every order, newest first, or only those with an open problem report (oldest report first). */
    @Transactional(readOnly = true)
    public PageResponse<AdminOrderResponse> all(boolean reportedOnly, int page, int size) {
        if (reportedOnly) {
            Pageable pageable = PageRequest.of(validPage(page), validSize(size),
                    Sort.by(Sort.Direction.ASC, "problemReportedAt").and(Sort.by(Sort.Direction.ASC, "id")));
            return PageResponse.of(orderRepository.findByProblemReportedAtIsNotNullAndStatus(OrderStatus.PAID, pageable),
                    AdminOrderResponse::from);
        }
        return PageResponse.of(orderRepository.findAll(pageable(page, size)), AdminOrderResponse::from);
    }

    /**
     * Admin: records that the seller has been paid out (outside this system, until Stripe Connect). Only a
     * COMPLETED order can be paid out: not one still waiting for the buyer, not one frozen by a report, not a
     * cancelled one. Idempotent: an already-paid-out order is returned unchanged.
     */
    @Transactional
    public AdminOrderResponse markPaidOut(Long orderId) {
        Order order = lock(orderId);
        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw new ConflictException(order.getProblemReportedAt() != null && order.getStatus() == OrderStatus.PAID
                    ? "This order has a problem report open and can't be paid out yet"
                    : "Only a completed order can be paid out");
        }
        if (order.getPayoutStatus() != PayoutStatus.PAID_OUT) {
            order.setPayoutStatus(PayoutStatus.PAID_OUT);
            log.info("Admin {} marked order {} as paid out", currentUser.requireId(), orderId);
        }
        return AdminOrderResponse.from(order);
    }

    /**
     * Admin: closes a problem report. COMPLETE means the buyer did get the item (the order completes and the
     * seller can be paid out); CANCEL cancels the order (no payout; any refund to the buyer is done in the Stripe
     * dashboard for now).
     */
    @Transactional
    public AdminOrderResponse resolveProblem(Long orderId, Resolution resolution) {
        Order order = lock(orderId);
        if (!order.hasOpenProblem()) {
            throw new ConflictException("This order has no open problem report");
        }
        if (resolution == Resolution.COMPLETE) {
            order.setStatus(OrderStatus.COMPLETED);
            order.setCompletedAt(Instant.now());
        } else {
            order.setStatus(OrderStatus.CANCELLED);
        }
        log.info("Admin {} resolved the problem on order {} as {}", currentUser.requireId(), orderId, resolution);
        return AdminOrderResponse.from(order);
    }

    private Order lock(Long orderId) {
        return orderRepository.findForUpdateById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " not found"));
    }

    /** 404 if it doesn't exist; 403 unless the caller is the order's buyer. */
    /** One query for the whole page, so listing orders doesn't cost a rating lookup per order. */
    private java.util.Map<Long, com.campus.marketplace.entity.Rating> ratingsByOrder(java.util.List<Order> orders) {
        if (orders.isEmpty()) {
            return java.util.Map.of();
        }
        return ratingRepository.findByOrderIds(orders.stream().map(Order::getId).toList()).stream()
                .collect(java.util.stream.Collectors.toMap(r -> r.getOrder().getId(), r -> r));
    }

    private static OrderResponse withRating(OrderResponse response, com.campus.marketplace.entity.Rating rating) {
        return rating == null ? response : response.withRating(rating);
    }

    private Order lockOwnedByBuyer(Long orderId) {
        Order order = lock(orderId);
        if (!Objects.equals(order.getBuyer().getId(), currentUser.requireId())) {
            throw new ForbiddenException("Only the buyer can do this");
        }
        return order;
    }

    private static int validPage(int page) {
        if (page < 0) {
            throw new BadRequestException("page must be 0 or greater");
        }
        return page;
    }

    private static int validSize(int size) {
        if (size < 1 || size > ListingService.MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + ListingService.MAX_PAGE_SIZE);
        }
        return size;
    }

    private static Pageable pageable(int page, int size) {
        return PageRequest.of(validPage(page), validSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
    }
}
