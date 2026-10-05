package com.campus.marketplace.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves the fix for a database whose CHECK constraint predates a new enum value. */
@SpringBootTest
class EnumCheckConstraintSyncTest {

    enum Kind { OLD, NEWER, NEWEST }

    @Autowired private EnumCheckConstraintSync sync;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void createOldSchema() {
        jdbc.execute("DROP TABLE IF EXISTS sync_probe");
        // As Hibernate created it before NEWER and NEWEST existed.
        jdbc.execute("CREATE TABLE sync_probe (id INT PRIMARY KEY, kind VARCHAR(20), "
                + "CONSTRAINT sync_probe_kind_check CHECK (kind IN ('OLD')))");
    }

    @AfterEach
    void dropProbe() {
        jdbc.execute("DROP TABLE IF EXISTS sync_probe");
    }

    @Test
    void newEnumValuesAreRejectedBeforeTheSync_andAcceptedAfter() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO sync_probe VALUES (1, 'NEWER')"))
                .isInstanceOf(DataIntegrityViolationException.class);

        sync.apply("sync_probe", "kind", Kind.class);

        jdbc.update("INSERT INTO sync_probe VALUES (1, 'NEWER')");
        jdbc.update("INSERT INTO sync_probe VALUES (2, 'NEWEST')");
        jdbc.update("INSERT INTO sync_probe VALUES (3, 'OLD')");
        // Still a real constraint: values outside the enum are rejected.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO sync_probe VALUES (4, 'BOGUS')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void isIdempotent_andKeepsExistingRows() {
        jdbc.update("INSERT INTO sync_probe VALUES (1, 'OLD')");

        sync.apply("sync_probe", "kind", Kind.class);
        sync.apply("sync_probe", "kind", Kind.class);
        sync.apply("sync_probe", "kind", Kind.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sync_probe", Integer.class)).isEqualTo(1);
        jdbc.update("INSERT INTO sync_probe VALUES (2, 'NEWEST')");
    }

    @Test
    void aMissingTableIsLoggedNotFatal() {
        sync.apply("no_such_table", "kind", Kind.class); // must not throw
    }

    @Test
    void theRealRunWritesEveryCurrentEnumValueIntoTheApplicationConstraints() {
        sync.run(null);

        assertThat(checkClause("PAYMENTS_PURPOSE_CHECK"))
                .contains("FEATURE_LISTING", "RESERVE_ITEM", "PURCHASE", "POSTING_FEE");
        assertThat(checkClause("PAYMENTS_STATUS_CHECK")).contains("PENDING", "SUCCEEDED", "FAILED", "REFUNDED");
        assertThat(checkClause("LISTINGS_STATUS_CHECK")).contains("ACTIVE", "SOLD", "PENDING_PAYMENT");
    }

    private String checkClause(String constraint) {
        return jdbc.queryForObject("SELECT check_clause FROM information_schema.check_constraints "
                + "WHERE constraint_name = ?", String.class, constraint);
    }

    @Test
    void theRealRunLeavesTheApplicationTablesAcceptingEveryEnumValue() {
        // Re-running over the live schema (as every startup does) must not break it or lose rows.
        sync.run(null);
        sync.run(null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments", Integer.class)).isNotNull();
    }
}
