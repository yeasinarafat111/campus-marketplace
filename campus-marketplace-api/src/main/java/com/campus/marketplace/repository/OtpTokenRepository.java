package com.campus.marketplace.repository;

import com.campus.marketplace.entity.OtpPurpose;
import com.campus.marketplace.entity.OtpToken;
import com.campus.marketplace.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OtpTokenRepository extends JpaRepository<OtpToken, Long> {

    /** The most recently issued unused code for this user and purpose, if any. */
    Optional<OtpToken> findTopByUserAndPurposeAndUsedFalseOrderByExpiresAtDesc(User user, OtpPurpose purpose);

    /** The most recent code (used or not) with this value, to tell "already used" apart from "wrong". */
    Optional<OtpToken> findTopByUserAndPurposeAndCodeOrderByIdDesc(User user, OtpPurpose purpose, String code);

    /** The most recently issued code for this user and purpose, used or not (for the resend cooldown). */
    Optional<OtpToken> findTopByUserAndPurposeOrderByIdDesc(User user, OtpPurpose purpose);

    /** Marks every unused code for this user and purpose as used. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OtpToken t set t.used = true where t.user = :user and t.purpose = :purpose and t.used = false")
    int invalidateActive(@Param("user") User user, @Param("purpose") OtpPurpose purpose);
}
