package com.campus.marketplace.service;

import com.campus.marketplace.dto.OrderResponse;
import com.campus.marketplace.dto.PageResponse;
import com.campus.marketplace.dto.RatingRequest;
import com.campus.marketplace.dto.SellerReviewResponse;
import com.campus.marketplace.entity.Order;
import com.campus.marketplace.entity.OrderStatus;
import com.campus.marketplace.entity.Rating;
import com.campus.marketplace.exception.BadRequestException;
import com.campus.marketplace.exception.ConflictException;
import com.campus.marketplace.exception.ForbiddenException;
import com.campus.marketplace.exception.ResourceNotFoundException;
import com.campus.marketplace.repository.OrderRepository;
import com.campus.marketplace.repository.RatingRepository;
import com.campus.marketplace.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Objects;

/** Buyers rate the seller once per completed order. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RatingService {

    private static final int MAX_PAGE_SIZE = 50;

    private final OrderRepository orderRepository;
    private final RatingRepository ratingRepository;
    private final CurrentUserProvider currentUser;

    @Value("${app.orders.auto-complete-after:72h}")
    private Duration autoCompleteAfter;

    /** Buyer only, COMPLETED orders only, once. The order row is locked so two taps can't both pass the check. */
    @Transactional
    public OrderResponse rate(Long orderId, RatingRequest request) {
        Order order = orderRepository.findForUpdateById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " not found"));
        if (!Objects.equals(order.getBuyer().getId(), currentUser.requireId())) {
            throw new ForbiddenException("Only the buyer can rate this order");
        }
        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw new ConflictException("You can rate the seller once the order is complete");
        }
        if (ratingRepository.existsByOrderId(orderId)) {
            throw new ConflictException("You've already rated this order");
        }
        Rating rating;
        try {
            rating = ratingRepository.saveAndFlush(Rating.builder()
                    .order(order).seller(order.getSeller()).buyer(order.getBuyer())
                    .stars(request.stars()).comment(request.comment()).build());
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("You've already rated this order");
        }
        log.info("Buyer {} rated seller {} {} stars for order {}", order.getBuyer().getId(), order.getSeller().getId(), request.stars(), orderId);
        return OrderResponse.forBuyer(order, autoCompleteAfter).withRating(rating);
    }

    /** The latest reviews of a seller, newest first. Public. */
    @Transactional(readOnly = true)
    public PageResponse<SellerReviewResponse> reviews(Long sellerId, int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return PageResponse.of(ratingRepository.findBySellerId(sellerId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")))),
                SellerReviewResponse::from);
    }
}
