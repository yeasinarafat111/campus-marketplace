package com.campus.marketplace.repository;

import com.campus.marketplace.entity.Payment;
import com.campus.marketplace.entity.PaymentPurpose;
import com.campus.marketplace.entity.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByStripeSessionId(String stripeSessionId);

    /** The deposit a buyer paid for a listing (the newest successful one, if they reserved more than once). */
    Optional<Payment> findFirstByListingIdAndUserIdAndPurposeAndStatusOrderByIdDesc(
            Long listingId, Long userId, PaymentPurpose purpose, PaymentStatus status);

    /** Deposits of reservations that are still running: the newest successful deposit of each reserved listing. */
    @Query("""
            select coalesce(sum(p.amount), 0) from Payment p
            where p.purpose = com.campus.marketplace.entity.PaymentPurpose.RESERVE_ITEM
              and p.status = com.campus.marketplace.entity.PaymentStatus.SUCCEEDED
              and exists (select 1 from Listing l where l.id = p.listing.id and l.reservedBy.id = p.user.id)
              and p.id = (select max(p2.id) from Payment p2 where p2.listing.id = p.listing.id and p2.user.id = p.user.id
                          and p2.purpose = com.campus.marketplace.entity.PaymentPurpose.RESERVE_ITEM
                          and p2.status = com.campus.marketplace.entity.PaymentStatus.SUCCEEDED)""")
    long sumHeldDeposits();

    /** Successful payments of a purpose that have no ledger entry of the given type yet (for the backfill). */
    @Query("""
            select p from Payment p
            where p.purpose = :purpose and p.status = com.campus.marketplace.entity.PaymentStatus.SUCCEEDED
              and not exists (select 1 from LedgerEntry e where e.paymentId = p.id and e.type = :type)
            order by p.id""")
    List<Payment> findSucceededWithoutLedgerEntry(@Param("purpose") PaymentPurpose purpose,
                                                  @Param("type") com.campus.marketplace.entity.LedgerType type);

    /** SELECT … FOR UPDATE, so concurrent deliveries of the same webhook are processed one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.stripeSessionId = :sessionId")
    Optional<Payment> findForUpdateByStripeSessionId(@Param("sessionId") String stripeSessionId);
}
