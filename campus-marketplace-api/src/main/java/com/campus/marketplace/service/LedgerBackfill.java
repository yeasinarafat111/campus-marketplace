package com.campus.marketplace.service;

import com.campus.marketplace.entity.LedgerEntry;
import com.campus.marketplace.entity.LedgerType;
import com.campus.marketplace.entity.Payment;
import com.campus.marketplace.entity.PaymentPurpose;
import com.campus.marketplace.repository.LedgerEntryRepository;
import com.campus.marketplace.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Books the feature fees that were paid before the ledger existed: every succeeded FEATURE_LISTING payment gets
 * one FEATURE_FEE entry, dated when it was paid. Idempotent: payments that already have their entry are skipped,
 * so running it on every start (or from several instances) adds nothing the second time.
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class LedgerBackfill implements ApplicationRunner {

    private final PaymentRepository paymentRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final TransactionTemplate transactions;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int added = backfillFeatureFees();
            if (added > 0) {
                log.info("Ledger backfill: booked {} earlier feature fee(s)", added);
            }
        } catch (RuntimeException e) {
            log.warn("Ledger backfill failed (will retry on the next start): {}", e.getMessage());
        }
    }

    /** @return how many entries were added */
    public int backfillFeatureFees() {
        List<Payment> missing = transactions.execute(tx ->
                paymentRepository.findSucceededWithoutLedgerEntry(PaymentPurpose.FEATURE_LISTING, LedgerType.FEATURE_FEE));
        int added = 0;
        for (Payment p : missing == null ? List.<Payment>of() : missing) {
            try {
                Boolean saved = transactions.execute(tx -> {
                    Payment payment = paymentRepository.findById(p.getId()).orElse(null);
                    if (payment == null || ledgerRepository.existsByPaymentIdAndType(payment.getId(), LedgerType.FEATURE_FEE)) {
                        return false;
                    }
                    ledgerRepository.saveAndFlush(LedgerEntry.builder()
                            .type(LedgerType.FEATURE_FEE).amountCents(payment.getAmount()).currency(payment.getCurrency())
                            .paymentId(payment.getId())
                            .listingId(payment.getListing() == null ? null : payment.getListing().getId())
                            .listingTitle(payment.getListing() == null ? null : payment.getListing().getTitle())
                            .userId(payment.getUser().getId()).occurredAt(payment.getCreatedAt()).build());
                    return true;
                });
                if (Boolean.TRUE.equals(saved)) {
                    added++;
                }
            } catch (DataIntegrityViolationException e) {
                // Another instance booked it first: the unique key did its job.
            }
        }
        return added;
    }
}
