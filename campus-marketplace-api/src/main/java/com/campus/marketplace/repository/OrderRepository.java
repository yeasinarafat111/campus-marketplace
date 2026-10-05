package com.campus.marketplace.repository;

import com.campus.marketplace.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

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
}
