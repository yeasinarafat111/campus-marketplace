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
import com.campus.marketplace.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
        return authService.signup(request);
    }

    @PostMapping("/verify-otp")
    public VerifyOtpResponse verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        return authService.verifyEmail(request);
    }

    @PostMapping("/resend-otp")
    public MessageResponse resendOtp(@Valid @RequestBody ResendOtpRequest request) {
        return authService.resendSignupOtp(request);
    }

    @PostMapping("/forgot-password")
    public MessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return authService.forgotPassword(request);
    }

    @PostMapping("/reset-password")
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return authService.resetPassword(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public AccessTokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request);
    }
}
