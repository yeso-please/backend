package com.yeso.backend.auth.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.yeso.backend.auth.application.AuthService;
import com.yeso.backend.shared.web.CurrentUserId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "1. 인증", description = "docs/api/auth.md §1")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;

    @Operation(summary = "1-5 내 정보")
    @GetMapping("/me")
    public UserMeResponse me(@CurrentUserId Long userId) {
        return authService.getMe(userId);
    }
}
