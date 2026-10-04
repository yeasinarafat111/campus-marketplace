package com.campus.marketplace.repository;

import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void findByEmail_returnsSavedUserWithDefaults() {
        userRepository.saveAndFlush(newUser("alice@campus.edu"));

        User found = userRepository.findByEmail("alice@campus.edu").orElseThrow();

        assertThat(found.getId()).isNotNull();
        assertThat(found.getRole()).isEqualTo(Role.STUDENT);
        assertThat(found.isEnabled()).isFalse();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void findByEmail_returnsEmptyForUnknownEmail() {
        assertThat(userRepository.findByEmail("nobody@campus.edu")).isEmpty();
    }

    @Test
    void email_mustBeUnique() {
        userRepository.saveAndFlush(newUser("dup@campus.edu"));

        assertThatThrownBy(() -> userRepository.saveAndFlush(newUser("dup@campus.edu")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static User newUser(String email) {
        return User.builder()
                .fullName("Test User")
                .email(email)
                .password("hashed")
                .build();
    }
}
