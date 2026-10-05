package com.campus.marketplace.config;

import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.PaymentPurpose;
import com.campus.marketplace.entity.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Keeps the database's CHECK constraints on enum columns in step with the Java enums.
 * <p>
 * Hibernate creates {@code CHECK (purpose IN ('A','B'))} for a string enum column, but {@code ddl-auto=update}
 * never changes an existing constraint. So adding an enum value (a new payment purpose or status) would make
 * every insert of it fail in a database that already has the table, until the schema moves to Flyway.
 * On startup this drops and re-adds the constraint with the current values: idempotent, in one transaction,
 * and safe if several instances start at once (the second waits for the first's lock).
 * <p>
 * It touches exactly these constraints and nothing else (no other table, column or constraint kind):
 * {@code payments_purpose_check}, {@code payments_status_check} and {@code listings_status_check}.
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class EnumCheckConstraintSync implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    @Override
    public void run(ApplicationArguments args) {
        apply("payments", "purpose", PaymentPurpose.class);
        apply("payments", "status", PaymentStatus.class);
        apply("listings", "status", ListingStatus.class);
    }

    /** Constraint name follows Hibernate's convention, {@code <table>_<column>_check}. */
    void apply(String table, String column, Class<? extends Enum<?>> enumType) {
        String constraint = table + "_" + column + "_check";
        String values = Arrays.stream(enumType.getEnumConstants())
                .map(e -> "'" + e.name() + "'")
                .collect(Collectors.joining(", "));
        try {
            transactions.executeWithoutResult(tx -> {
                jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + constraint);
                jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + constraint
                        + " CHECK (" + column + " IN (" + values + "))");
            });
            log.info("Synced CHECK constraint {} to {}", constraint, values);
        } catch (RuntimeException e) {
            // Not fatal at startup (e.g. the table doesn't exist yet), but a missing value would surface later.
            log.warn("Could not sync CHECK constraint {} on {}: {}", constraint, table, e.getMessage());
        }
    }
}
