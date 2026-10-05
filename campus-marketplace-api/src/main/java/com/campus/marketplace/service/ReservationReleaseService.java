package com.campus.marketplace.service;

import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.PaymentPurpose;
import com.campus.marketplace.entity.PaymentStatus;
import com.campus.marketplace.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ends a reservation in which the buyer loses their deposit (decision D5): the buyer released the item, or the
 * time ran out. The deposit becomes platform revenue (a FORFEITED_DEPOSIT ledger entry) and the item is freed.
 * Every such path goes through here, so the revenue is booked exactly once, in the same transaction.
 */
@Service
@RequiredArgsConstructor
public class ReservationReleaseService {

    private final PaymentRepository paymentRepository;
    private final LedgerService ledgerService;

    /** The listing must be locked and reserved. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void forfeitAndRelease(Listing listing) {
        paymentRepository.findFirstByListingIdAndUserIdAndPurposeAndStatusOrderByIdDesc(
                        listing.getId(), listing.getReservedBy().getId(), PaymentPurpose.RESERVE_ITEM, PaymentStatus.SUCCEEDED)
                .ifPresent(deposit -> ledgerService.recordForfeitedDeposit(deposit, listing));
        listing.clearReservation();
    }
}
