package com.campus.marketplace.service;

import com.campus.marketplace.config.PaymentProperties;
import com.campus.marketplace.dto.LedgerEntryResponse;
import com.campus.marketplace.dto.PageResponse;
import com.campus.marketplace.dto.RevenueReport;
import com.campus.marketplace.entity.LedgerEntry;
import com.campus.marketplace.entity.LedgerType;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.exception.BadRequestException;
import com.campus.marketplace.repository.LedgerEntryRepository;
import com.campus.marketplace.repository.OrderRepository;
import com.campus.marketplace.repository.PaymentRepository;
import com.campus.marketplace.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Admin reporting over the revenue ledger. Days are campus days (Asia/Dhaka by default), the range is inclusive of
 * both end dates, and all figures are before Stripe's processing fees.
 */
@Service
@RequiredArgsConstructor
public class RevenueService {

    public enum GroupBy {
        DAY, WEEK, MONTH;

        /** "day", "week" or "month", in any case. */
        public static GroupBy parse(String value) {
            try {
                return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("groupBy must be day, week or month");
            }
        }
    }

    static final int MAX_RANGE_DAYS = 366;
    static final int MAX_EXPORT_ROWS = 50_000;
    private static final int DEFAULT_RANGE_DAYS = 30;

    private final LedgerEntryRepository ledgerRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentProperties paymentProperties;

    @Value("${app.reporting.zone:Asia/Dhaka}")
    private String zoneId;

    /** Totals per source, net revenue, a zero-filled series, and what is owed to sellers right now. */
    @Transactional(readOnly = true)
    public RevenueReport report(LocalDate from, LocalDate to, GroupBy groupBy) {
        ZoneId zone = ZoneId.of(zoneId);
        LocalDate end = to != null ? to : LocalDate.now(zone);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1L);
        validateRange(start, end);

        List<LedgerEntry> entries = ledgerRepository
                .findByOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAscIdAsc(
                        startOf(start, zone), startOf(end.plusDays(1), zone));

        Map<LocalDate, Map<LedgerType, Long>> buckets = new LinkedHashMap<>();
        for (LocalDate b = bucketOf(start, groupBy); !b.isAfter(end); b = next(b, groupBy)) {
            buckets.put(b, zeros());
        }
        Map<LedgerType, Long> totals = zeros();
        for (LedgerEntry e : entries) {
            LocalDate bucket = bucketOf(e.getOccurredAt().atZone(zone).toLocalDate(), groupBy);
            buckets.computeIfAbsent(bucket, k -> zeros()).merge(e.getType(), e.getAmountCents(), Long::sum);
            totals.merge(e.getType(), e.getAmountCents(), Long::sum);
        }
        List<RevenueReport.Bucket> series = buckets.entrySet().stream()
                .map(b -> new RevenueReport.Bucket(b.getKey().toString(), b.getValue(), sum(b.getValue())))
                .toList();
        return new RevenueReport(start, end, groupBy.name().toLowerCase(), paymentProperties.currency(), totals,
                sum(totals), series, orderRepository.sumOwedToSellers(), paymentRepository.sumHeldDeposits());
    }

    /** The history table, newest first, with the payer and listing. Dates are optional here. */
    @Transactional(readOnly = true)
    public PageResponse<LedgerEntryResponse> entries(LocalDate from, LocalDate to, LedgerType type, int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must be 0 or greater");
        }
        if (size < 1 || size > ListingService.MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + ListingService.MAX_PAGE_SIZE);
        }
        ZoneId zone = ZoneId.of(zoneId);
        Page<LedgerEntry> result = ledgerRepository.search(lower(from, zone), upper(to, zone), type, PageRequest.of(page, size));
        Map<Long, User> users = usersOf(result.getContent());
        return PageResponse.of(result, e -> LedgerEntryResponse.of(e, userRef(users.get(e.getUserId()))));
    }

    /** The same entries as a CSV file, oldest first. Text cells that start like a formula are neutralised. */
    @Transactional(readOnly = true)
    public String csv(LocalDate from, LocalDate to, LedgerType type) {
        ZoneId zone = ZoneId.of(zoneId);
        List<LedgerEntry> entries = ledgerRepository.searchAll(lower(from, zone), upper(to, zone), type,
                PageRequest.of(0, MAX_EXPORT_ROWS));
        Map<Long, User> users = usersOf(entries);
        StringBuilder out = new StringBuilder("id,date,type,listing_id,listing_title,user_email,payment_id,amount,currency\r\n");
        for (LedgerEntry e : entries) {
            User user = users.get(e.getUserId());
            out.append(e.getId()).append(',')
                    .append(e.getOccurredAt().atZone(zone).toLocalDateTime()).append(',')
                    .append(e.getType()).append(',')
                    .append(e.getListingId() == null ? "" : e.getListingId()).append(',')
                    .append(cell(e.getListingTitle())).append(',')
                    .append(cell(user == null ? null : user.getEmail())).append(',')
                    .append(e.getPaymentId() == null ? "" : e.getPaymentId()).append(',')
                    .append(BigDecimal.valueOf(e.getAmountCents(), 2).toPlainString()).append(',')
                    .append(e.getCurrency()).append("\r\n");
        }
        return out.toString();
    }

    // ---- helpers ----

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BadRequestException("from must not be after to");
        }
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new BadRequestException("The date range can be at most " + MAX_RANGE_DAYS + " days");
        }
    }

    private static Instant startOf(LocalDate day, ZoneId zone) {
        return day.atStartOfDay(zone).toInstant();
    }

    private static Instant lower(LocalDate from, ZoneId zone) {
        return from == null ? Instant.EPOCH : startOf(from, zone);
    }

    private static Instant upper(LocalDate to, ZoneId zone) {
        return to == null ? Instant.parse("9999-01-01T00:00:00Z") : startOf(to.plusDays(1), zone);
    }

    private static LocalDate bucketOf(LocalDate day, GroupBy groupBy) {
        return switch (groupBy) {
            case DAY -> day;
            case WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> day.withDayOfMonth(1);
        };
    }

    private static LocalDate next(LocalDate bucket, GroupBy groupBy) {
        return switch (groupBy) {
            case DAY -> bucket.plusDays(1);
            case WEEK -> bucket.plusWeeks(1);
            case MONTH -> bucket.plusMonths(1);
        };
    }

    private static Map<LedgerType, Long> zeros() {
        Map<LedgerType, Long> map = new EnumMap<>(LedgerType.class);
        for (LedgerType t : LedgerType.values()) {
            map.put(t, 0L);
        }
        return map;
    }

    private static long sum(Map<LedgerType, Long> byType) {
        return byType.values().stream().mapToLong(Long::longValue).sum();
    }

    private Map<Long, User> usersOf(List<LedgerEntry> entries) {
        var ids = entries.stream().map(LedgerEntry::getUserId).filter(java.util.Objects::nonNull).distinct().toList();
        return userRepository.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private static LedgerEntryResponse.UserRef userRef(User user) {
        return user == null ? null : new LedgerEntryResponse.UserRef(user.getId(), user.getFullName(), user.getEmail());
    }

    /** A CSV text cell: quoted, with quotes doubled, and prefixed with an apostrophe if a spreadsheet would run it as a formula. */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String safe = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
