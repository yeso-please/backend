package com.yeso.backend.auth.presentation;

import com.yeso.backend.auth.domain.User;

import java.util.List;

public record UserMeResponse(
        Long id,
        String email,
        String nickname,
        String profileImage,
        boolean onboardingCompleted,
        /** 이 회원이 로그인할 수 있는 방법. {@code EMAIL}, {@code KAKAO} */
        List<String> loginMethods
) {
    public static UserMeResponse of(User user, boolean onboardingCompleted, List<String> loginMethods) {
        return new UserMeResponse(
                user.getId(), user.getEmail(), user.getNickname(), user.getProfileImage(), onboardingCompleted,
                List.copyOf(loginMethods));
    }
}
