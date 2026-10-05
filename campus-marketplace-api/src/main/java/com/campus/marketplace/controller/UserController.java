package com.campus.marketplace.controller;

import com.campus.marketplace.dto.AuthResponse;
import com.campus.marketplace.dto.ChangePasswordRequest;
import com.campus.marketplace.dto.CurrentUserResponse;
import com.campus.marketplace.dto.UpdateProfileRequest;
import com.campus.marketplace.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** The caller's own profile (including phone), so the SPA can show who is logged in. */
    @GetMapping("/me")
    public CurrentUserResponse me() {
        return userService.getMe();
    }

    @PatchMapping("/me")
    public CurrentUserResponse updateMe(@Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(request);
    }

    /** Ends every other session and returns a fresh token pair for this one. */
    @PostMapping("/me/password")
    public AuthResponse changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return userService.changePassword(request);
    }
}
