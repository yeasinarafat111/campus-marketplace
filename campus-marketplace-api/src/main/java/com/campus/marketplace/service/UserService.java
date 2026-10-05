package com.campus.marketplace.service;

import com.campus.marketplace.dto.AuthResponse;
import com.campus.marketplace.dto.ChangePasswordRequest;
import com.campus.marketplace.dto.CurrentUserResponse;
import com.campus.marketplace.dto.UpdateProfileRequest;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.exception.BadRequestException;
import com.campus.marketplace.exception.ConflictException;
import com.campus.marketplace.exception.UnauthorizedException;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.CurrentUserProvider;
import com.campus.marketplace.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserProvider currentUser;
    private final AuthService authService;

    /** Loaded from the database: the principal doesn't carry the name or phone. */
    @Transactional(readOnly = true)
    public CurrentUserResponse getMe() {
        return CurrentUserResponse.from(loadCaller());
    }

    @Transactional
    public CurrentUserResponse updateProfile(UpdateProfileRequest request) {
        User user = loadCaller();
        if (request.fullName() != null) {
            user.setFullName(request.fullName());
        }
        if (request.phone() != null && !request.phone().equals(user.getPhone())) {
            if (userRepository.existsByPhoneAndIdNot(request.phone(), user.getId())) {
                throw new ConflictException("Phone number is already registered");
            }
            user.setPhone(request.phone());
        }
        // Flush now so a concurrent duplicate phone fails here, as a 409, rather than at commit.
        userRepository.flush();
        return CurrentUserResponse.from(user);
    }

    /**
     * Sets {@code passwordChangedAt}, which rejects every token issued before now (other browsers and
     * devices are logged out), and returns a fresh token pair so the caller's own session carries on.
     */
    @Transactional
    public AuthResponse changePassword(ChangePasswordRequest request) {
        User user = loadCaller();
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new BadRequestException("Current password is incorrect");
        }
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.setPasswordChangedAt(Instant.now());
        return authService.issueTokens(UserPrincipal.from(user));
    }

    private User loadCaller() {
        return userRepository.findById(currentUser.requireId())
                .orElseThrow(() -> new UnauthorizedException("Authentication required"));
    }
}
