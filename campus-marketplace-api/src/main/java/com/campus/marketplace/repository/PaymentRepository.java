package com.campus.marketplace.repository;

import com.campus.marketplace.entity.Payment;
import com.campus.marketplace.entity.PaymentPurpose;
import com.campus.marketplace.entity.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByStripeSessionId(String stripeSessionId);

    /** The deposit a buyer paid for a listing (the newest successful one, if they reserved more than once). */
    Optional<Payment> findFirstByListingIdAndUserIdAndPurposeAndStatusOrderByIdDesc(
            Long listingId, Long userId, PaymentPurpose purpose, PaymentStatus status);

    /** SELECT … FOR UPDATE, so concurrent deliveries of the same webhook are processed one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.stripeSessionId = :sessionId")
    Optional<Payment> findForUpdateByStripeSessionId(@Param("sessionId") String stripeSessionId);
}
