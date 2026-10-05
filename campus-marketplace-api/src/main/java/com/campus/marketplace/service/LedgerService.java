package com.campus.marketplace.service;

import com.campus.marketplace.entity.LedgerEntry;
import com.campus.marketplace.entity.LedgerType;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.Payment;
import com.campus.marketplace.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Writes the revenue ledger. Every method joins the caller's transaction (MANDATORY), so an entry exists exactly
 * when the event it records was committed, and never for one that rolled back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerEntryRepository ledgerRepository;

    /** The platform keeps a feature fee, whatever happens to the listing afterwards. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordFeatureFee(Payment payment, Listing listing) {
        record(LedgerType.FEATURE_FEE, payment.getAmount(), payment, listing == null ? null : listing.getId(),
                listing == null ? null : listing.getTitle(), Instant.now());
    }

    /** A posting fee that published a listing. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPostingFee(Payment payment, Listing listing) {
        record(LedgerType.POSTING_FEE, payment.getAmount(), payment, listing.getId(), listing.getTitle(), Instant.now());
    }

    /** A deposit the platform now keeps: the buyer released the item, or the reservation ran out. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordForfeitedDeposit(Payment deposit, Listing listing) {
        record(LedgerType.FORFEITED_DEPOSIT, deposit.getAmount(), deposit, listing.getId(), listing.getTitle(), Instant.now());
    }

    /**
     * A payment is being refunded: take back whatever revenue it earned, as a new negative entry (once, however
     * often this is called). A payment that earned nothing (a held deposit, a purchase) adds nothing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reverseRevenue(Payment payment) {
        for (LedgerType type : LedgerType.values()) {
            List<LedgerEntry> entries = ledgerRepository.findByPaymentIdAndTypeOrderById(payment.getId(), type);
            long net = entries.stream().mapToLong(LedgerEntry::getAmountCents).sum();
            if (net > 0) {
                LedgerEntry original = entries.get(0);
                ledgerRepository.save(LedgerEntry.builder()
                        .type(type).amountCents(-net).currency(original.getCurrency())
                        .paymentId(payment.getId()).listingId(original.getListingId()).listingTitle(original.getListingTitle())
                        .userId(original.getUserId()).occurredAt(Instant.now()).build());
                log.info("Reversed {} {} of revenue for refunded payment {}", net, type, payment.getId());
            }
        }
    }

    private void record(LedgerType type, long amountCents, Payment payment, Long listingId, String listingTitle, Instant at) {
        if (ledgerRepository.existsByPaymentIdAndType(payment.getId(), type)) {
            return; // already booked (a replayed event)
        }
        ledgerRepository.save(LedgerEntry.builder()
                .type(type).amountCents(amountCents).currency(payment.getCurrency())
                .paymentId(payment.getId()).listingId(listingId).listingTitle(listingTitle)
                .userId(payment.getUser().getId()).occurredAt(at).build());
        log.info("Ledger: {} {} {} (payment {})", type, amountCents, payment.getCurrency(), payment.getId());
    }
}
