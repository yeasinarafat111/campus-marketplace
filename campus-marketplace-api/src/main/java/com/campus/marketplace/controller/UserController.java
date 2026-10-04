package com.campus.marketplace.controller;

import com.campus.marketplace.dto.CurrentUserResponse;
import com.campus.marketplace.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final CurrentUserProvider currentUser;

    /** Lets the SPA learn the caller's identity and role; the access token only carries the email. */
    @GetMapping("/me")
    public CurrentUserResponse me() {
        return CurrentUserResponse.from(currentUser.require());
    }
}
