package com.campus.marketplace.service;

import com.campus.marketplace.dto.AccessTokenResponse;
import com.campus.marketplace.dto.AuthResponse;
import com.campus.marketplace.dto.LoginRequest;
import com.campus.marketplace.dto.MessageResponse;
import com.campus.marketplace.dto.RefreshTokenRequest;
import com.campus.marketplace.dto.ResendOtpRequest;
import com.campus.marketplace.dto.SignupRequest;
import com.campus.marketplace.dto.SignupResponse;
import com.campus.marketplace.dto.VerifyOtpRequest;
import com.campus.marketplace.dto.VerifyOtpResponse;
import com.campus.marketplace.entity.OtpPurpose;
import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.exception.ConflictException;
import com.campus.marketplace.exception.ForbiddenException;
import com.campus.marketplace.exception.InvalidOtpException;
import com.campus.marketplace.exception.UnauthorizedException;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.JwtService;
import com.campus.marketplace.security.UserPrincipal;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String TOKEN_TYPE = "Bearer";
    private static final String ACCOUNT_DEACTIVATED = "Your account has been deactivated";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final OtpService otpService;
    private final MailService mailService;

    /**
     * Creates a disabled STUDENT account and emails a SIGNUP verification code. No tokens are issued:
     * the user must verify their email first.
     * <p>
     * The email is sent last, inside the transaction. If it fails, the user and code are rolled back
     * (the client gets a 503), so the same email can simply sign up again.
     */
    @Transactional
    public SignupResponse signup(SignupRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email is already registered");
        }

        // Flush now so a concurrent duplicate signup fails here, before any email is sent.
        User user = userRepository.saveAndFlush(User.builder()
                .fullName(request.fullName())
                .email(email)
                .password(passwordEncoder.encode(request.password()))
                .role(Role.STUDENT)
                .enabled(false)
                .build());

        String code = otpService.issue(user, OtpPurpose.SIGNUP);
        mailService.sendOtpEmail(user.getEmail(), code);

        return new SignupResponse(user.getId(), user.getFullName(), user.getEmail(),
                "Account created. We've sent a 6-digit verification code to your email.");
    }

    /**
     * Verifies a SIGNUP code and enables the account. Not rolled back on InvalidOtpException, so wrong
     * guesses are still counted.
     */
    @Transactional(noRollbackFor = InvalidOtpException.class)
    public VerifyOtpResponse verifyEmail(VerifyOtpRequest request) {
        // Unknown emails get the same message as a wrong code.
        User user = userRepository.findByEmail(normalizeEmail(request.email()))
                .orElseThrow(() -> new InvalidOtpException("Invalid verification code"));
        if (!user.isActive()) {
            throw new ForbiddenException(ACCOUNT_DEACTIVATED);
        }

        otpService.consume(user, OtpPurpose.SIGNUP, request.code());
        user.setEnabled(true);

        return new VerifyOtpResponse(user.getEmail(), true, "Email verified. You can now log in.");
    }

    /**
     * Emails a new SIGNUP code to an unverified account. Unknown and already-verified emails get the same
     * response, so this endpoint can't be used to discover which emails are registered.
     */
    @Transactional
    public MessageResponse resendSignupOtp(ResendOtpRequest request) {
        userRepository.findByEmail(normalizeEmail(request.email()))
                .filter(user -> !user.isEnabled() && user.isActive())
                .ifPresent(user -> {
                    otpService.checkResendCooldown(user, OtpPurpose.SIGNUP);
                    String code = otpService.issue(user, OtpPurpose.SIGNUP);
                    mailService.sendOtpEmail(user.getEmail(), code);
                });
        return new MessageResponse("If an unverified account exists for this email, a new code has been sent.");
    }

    /**
     * @throws org.springframework.security.authentication.BadCredentialsException wrong email or password (401)
     * @throws org.springframework.security.authentication.DisabledException correct password but email not verified (403)
     */
    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(normalizeEmail(request.email()), request.password()));
        UserPrincipal user = (UserPrincipal) authentication.getPrincipal();

        return new AuthResponse(
                jwtService.generateAccessToken(user),
                jwtService.generateRefreshToken(user),
                TOKEN_TYPE,
                jwtService.getAccessTokenExpiration().toSeconds());
    }

    @Transactional(readOnly = true)
    public AccessTokenResponse refresh(RefreshTokenRequest request) {
        String email;
        try {
            email = jwtService.validateRefreshToken(request.refreshToken());
        } catch (ExpiredJwtException e) {
            throw new UnauthorizedException("Refresh token has expired");
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid refresh token");
        }

        // Re-check the account: it may have been deleted or disabled since the refresh token was issued.
        UserPrincipal user = userRepository.findByEmail(email)
                .map(UserPrincipal::from)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));
        if (!user.isAccountNonLocked()) {
            throw new UnauthorizedException("Account has been deactivated");
        }
        if (!user.isEnabled()) {
            throw new UnauthorizedException("Account is not enabled");
        }

        return new AccessTokenResponse(
                jwtService.generateAccessToken(user),
                TOKEN_TYPE,
                jwtService.getAccessTokenExpiration().toSeconds());
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
