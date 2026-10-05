package com.campus.marketplace.repository;

import com.campus.marketplace.entity.Order;
import com.campus.marketplace.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** Buyer and seller are fetched together so mapping a page to DTOs costs no extra queries. */
    @EntityGraph(attributePaths = {"buyer", "seller"})
    Page<Order> findByBuyerId(Long buyerId, Pageable pageable);

    @EntityGraph(attributePaths = {"buyer", "seller"})
    Page<Order> findBySellerId(Long sellerId, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"buyer", "seller"})
    Page<Order> findAll(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findForUpdateById(@Param("id") Long id);

    boolean existsByPaymentId(Long paymentId);

    /** Orders with an open problem report, oldest report first: the admin queue. */
    @EntityGraph(attributePaths = {"buyer", "seller"})
    Page<Order> findByProblemReportedAtIsNotNullAndStatus(OrderStatus status, Pageable pageable);

    /**
     * Completes every PAID order paid at or before {@code cutoff} that has no problem report. One conditional
     * UPDATE: safe on several instances and idempotent. A report freezes an order, so reported ones are skipped.
     */
    @Modifying
    @Query("""
            update Order o set o.status = com.campus.marketplace.entity.OrderStatus.COMPLETED, o.completedAt = :now
            where o.status = com.campus.marketplace.entity.OrderStatus.PAID
              and o.paymentMethod = com.campus.marketplace.entity.PaymentMethod.ONLINE
              and o.problemReportedAt is null and o.createdAt <= :cutoff""")
    int autoCompleteDue(@Param("cutoff") Instant cutoff, @Param("now") Instant now);
}
