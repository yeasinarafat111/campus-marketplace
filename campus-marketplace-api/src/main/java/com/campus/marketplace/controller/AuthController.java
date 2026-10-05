package com.campus.marketplace.controller;

import com.campus.marketplace.dto.AccessTokenResponse;
import com.campus.marketplace.dto.AuthResponse;
import com.campus.marketplace.dto.ForgotPasswordRequest;
import com.campus.marketplace.dto.LoginRequest;
import com.campus.marketplace.dto.MessageResponse;
import com.campus.marketplace.dto.RefreshTokenRequest;
import com.campus.marketplace.dto.ResendOtpRequest;
import com.campus.marketplace.dto.ResetPasswordRequest;
import com.campus.marketplace.dto.SignupRequest;
import com.campus.marketplace.dto.SignupResponse;
import com.campus.marketplace.dto.VerifyOtpRequest;
import com.campus.marketplace.dto.VerifyOtpResponse;
import com.campus.marketplace.service.AuthRateLimiter;
import com.campus.marketplace.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthRateLimiter rateLimiter;

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest request, HttpServletRequest http) {
        rateLimiter.check(AuthRateLimiter.Action.SIGNUP, rateLimiter.clientIp(http), request.email());
        return authService.signup(request);
    }

    @PostMapping("/verify-otp")
    public VerifyOtpResponse verifyOtp(@Valid @RequestBody VerifyOtpRequest request, HttpServletRequest http) {
        rateLimiter.check(AuthRateLimiter.Action.VERIFY_OTP, rateLimiter.clientIp(http), request.email());
        return authService.verifyEmail(request);
    }

    @PostMapping("/resend-otp")
    public MessageResponse resendOtp(@Valid @RequestBody ResendOtpRequest request, HttpServletRequest http) {
        rateLimiter.check(AuthRateLimiter.Action.RESEND_OTP, rateLimiter.clientIp(http), request.email());
        return authService.resendSignupOtp(request);
    }

    @PostMapping("/forgot-password")
    public MessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest http) {
        rateLimiter.check(AuthRateLimiter.Action.FORGOT_PASSWORD, rateLimiter.clientIp(http), request.email());
        return authService.forgotPassword(request);
    }

    @PostMapping("/reset-password")
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request, HttpServletRequest http) {
        // A reset code is a guessable 6 digits, so it shares the code-checking limits.
        rateLimiter.check(AuthRateLimiter.Action.VERIFY_OTP, rateLimiter.clientIp(http), request.email());
        return authService.resetPassword(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        rateLimiter.checkLogin(rateLimiter.clientIp(http), request.email());
        try {
            AuthResponse response = authService.login(request);
            rateLimiter.loginSucceeded(request.email());
            return response;
        } catch (BadCredentialsException e) {
            rateLimiter.loginFailed(request.email());
            throw e;
        }
    }

    @PostMapping("/refresh")
    public AccessTokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request);
    }
}
