package com.campus.marketplace.service;

import com.campus.marketplace.exception.EmailDeliveryException;
import com.campus.marketplace.service.TradeEvents.Party;
import com.campus.marketplace.service.TradeEvents.PurchaseCompleted;
import com.campus.marketplace.service.TradeEvents.ReservationConfirmed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Emails both sides when a reservation or purchase has been committed. Runs after the commit (so a rolled
 * back payment never sends mail) and off the request thread (so a slow SMTP server can't hold up Stripe's
 * webhook). A failed email is logged and never affects the payment.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeNotifier {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ENGLISH).withZone(ZoneId.of("Asia/Dhaka"));

    private final MailService mailService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReservation(ReservationConfirmed e) {
        String link = listingLink(e.listingId());
        send(e.seller(), "Your item \"" + e.title() + "\" was reserved", """
                Hello %s,

                %s reserved "%s" and paid a %s deposit.

                Their phone: %s
                The reservation lasts until %s. Get in touch to arrange a meetup on campus.
                If you need to cancel the reservation, the deposit is refunded to them in full.

                %s

                — Campus Marketplace
                """.formatted(e.seller().name(), e.buyer().name(), e.title(), money(e.depositCents(), e.currency()),
                phone(e.buyer()), WHEN.format(e.expiresAt()), link));
        send(e.buyer(), "You reserved \"" + e.title() + "\"", """
                Hello %s,

                You reserved "%s" with a %s deposit. It is held for you until %s.

                Seller: %s
                Seller's phone: %s

                The deposit counts toward the price: when you buy, you pay the balance of %s.
                Buy it here before the reservation expires: %s

                If you cancel, or don't buy before it expires, the deposit is not refunded.

                — Campus Marketplace
                """.formatted(e.buyer().name(), e.title(), money(e.depositCents(), e.currency()),
                WHEN.format(e.expiresAt()), e.seller().name(), phone(e.seller()),
                money(e.priceCents() - e.depositCents(), e.currency()), link));
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPurchase(PurchaseCompleted e) {
        String link = listingLink(e.listingId());
        send(e.seller(), "\"" + e.title() + "\" was bought", """
                Hello %s,

                %s bought "%s" for %s (a %s deposit plus %s paid now).

                Their phone: %s
                Arrange the handover on campus. The money is held by Campus Marketplace for you.

                %s

                — Campus Marketplace
                """.formatted(e.seller().name(), e.buyer().name(), e.title(), money(e.priceCents(), e.currency()),
                money(e.depositCents(), e.currency()), money(e.balanceCents(), e.currency()),
                phone(e.buyer()), link));
        send(e.buyer(), "Purchase complete: \"" + e.title() + "\"", """
                Hello %s,

                Your purchase of "%s" is complete. You paid %s in total (%s deposit plus %s).

                Seller: %s
                Seller's phone: %s

                Meet on campus in a public place and inspect the item before handing it over.

                %s

                — Campus Marketplace
                """.formatted(e.buyer().name(), e.title(), money(e.priceCents(), e.currency()),
                money(e.depositCents(), e.currency()), money(e.balanceCents(), e.currency()),
                e.seller().name(), phone(e.seller()), link));
    }

    private void send(Party to, String subject, String body) {
        try {
            mailService.sendNotification(to.email(), subject, body);
        } catch (EmailDeliveryException ex) {
            // Already logged by MailService; the trade itself stands.
            log.warn("Notification '{}' to {} was not delivered", subject, to.email());
        } catch (RuntimeException ex) {
            log.error("Unexpected error sending notification '{}' to {}", subject, to.email(), ex);
        }
    }

    private String listingLink(Long listingId) {
        return frontendUrl.replaceAll("/+$", "") + "/listings/" + listingId;
    }

    private static String phone(Party party) {
        return party.phone() != null ? party.phone() : "not provided";
    }

    static String money(long cents, String currency) {
        String symbol = "usd".equalsIgnoreCase(currency) ? "$" : currency.toUpperCase(Locale.ROOT) + " ";
        return "%s%d.%02d".formatted(symbol, cents / 100, Math.abs(cents % 100));
    }
}
