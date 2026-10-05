package com.campus.marketplace.repository;

import com.campus.marketplace.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Accounts that can log in: email verified and not deactivated. */
    long countByEnabledTrueAndActiveTrue();


    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    boolean existsByPhoneAndIdNot(String phone, Long id);
}
