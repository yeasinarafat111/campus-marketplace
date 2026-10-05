package com.campus.marketplace.controller;

import com.campus.marketplace.dto.AuthResponse;
import com.campus.marketplace.dto.ChangePasswordRequest;
import com.campus.marketplace.dto.CurrentUserResponse;
import com.campus.marketplace.dto.UpdateProfileRequest;
import com.campus.marketplace.dto.ListingResponse;
import com.campus.marketplace.dto.PageResponse;
import com.campus.marketplace.service.SavedListingService;
import com.campus.marketplace.service.UserService;
import java.util.List;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final SavedListingService savedListingService;

    /** The caller's own profile (including phone), so the SPA can show who is logged in. */
    @GetMapping("/me")
    public CurrentUserResponse me() {
        return userService.getMe();
    }

    @PatchMapping("/me")
    public CurrentUserResponse updateMe(@Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(request);
    }

    /** The listings the caller saved, newest first. */
    @GetMapping("/me/saved")
    public PageResponse<ListingResponse> saved(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return savedListingService.mine(page, size);
    }

    /** Ids of everything the caller saved, for the heart on listing cards. */
    @GetMapping("/me/saved/ids")
    public List<Long> savedIds() {
        return savedListingService.mineIds();
    }

    /** Ends every other session and returns a fresh token pair for this one. */
    @PostMapping("/me/password")
    public AuthResponse changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return userService.changePassword(request);
    }
}
