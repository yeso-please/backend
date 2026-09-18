package com.yeso.backend.auth.presentation;

import com.yeso.backend.auth.application.AuthService;
import com.yeso.backend.shared.web.CurrentUser;
import com.yeso.backend.shared.web.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;

    @GetMapping("/me")
    public UserResponse me(@CurrentUser CustomUserDetails user) {
        return authService.getMe(user.userId());
    }
}
