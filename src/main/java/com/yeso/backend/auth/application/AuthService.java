package com.yeso.backend.auth.application;

import com.yeso.backend.auth.domain.DuplicateEmailException;
import com.yeso.backend.auth.domain.InvalidCredentialsException;
import com.yeso.backend.auth.domain.InvalidRefreshTokenException;
import com.yeso.backend.auth.domain.RefreshToken;
import com.yeso.backend.auth.domain.User;
import com.yeso.backend.auth.domain.UserNotFoundException;
import com.yeso.backend.auth.infrastructure.JwtProperties;
import com.yeso.backend.auth.infrastructure.JwtTokenProvider;
import com.yeso.backend.auth.infrastructure.RefreshTokenRepository;
import com.yeso.backend.auth.infrastructure.UserRepository;
import com.yeso.backend.auth.presentation.LoginRequest;
import com.yeso.backend.auth.presentation.SignupRequest;
import com.yeso.backend.auth.presentation.UserMeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;

    public IssuedTokens signup(SignupRequest request) {
        String email = request.email();
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }
        User user = new User(email, passwordEncoder.encode(request.password()), request.nickname());
        userRepository.save(user);
        return issueNewFamily(user);
    }

    public IssuedTokens login(LoginRequest request) {
        String email = request.email();
        User user = userRepository.findByEmail(email)
                .filter(u -> u.getPasswordHash() != null)
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return issueNewFamily(user);
    }

    /**
     * refresh row를 lock한 뒤 판정한다: 폐기된(이미 rotate된) 토큰이 다시 제시되면 탈취로 간주해
     * 같은 family를 통째로 무효화하고, 동시에 들어온 나머지 요청은 이 잠금 때문에 순서대로만
     * 처리돼 정확히 하나만 성공한다(WORK-01 계약).
     */
    // 재사용 탐지 시 family를 폐기한 뒤 401을 던지므로, 이 예외로는 롤백하지 않아야 폐기가 커밋된다.
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public IssuedTokens refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }
        String tokenHash = jwtTokenProvider.hash(refreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(InvalidRefreshTokenException::new);

        LocalDateTime now = LocalDateTime.now();
        if (stored.isRevoked()) {
            refreshTokenRepository.revokeActiveByFamilyId(stored.getFamilyId(), now);
            throw new InvalidRefreshTokenException();
        }
        if (stored.isExpired(now)) {
            throw new InvalidRefreshTokenException();
        }

        User user = stored.getUser();
        IssuedTokens issued = issueForFamily(user, stored.getFamilyId());
        stored.revokeAsReplaced(now, issued.refreshTokenId());
        return issued;
    }

    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        String tokenHash = jwtTokenProvider.hash(refreshToken);
        refreshTokenRepository.findByTokenHash(tokenHash)
                .filter(rt -> !rt.isRevoked())
                .ifPresent(rt -> rt.revoke(LocalDateTime.now()));
    }

    @Transactional(readOnly = true)
    public UserMeResponse getMe(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        // 온보딩 상태(WORK-02)는 아직 구현되지 않아 항상 false를 반환한다.
        return UserMeResponse.of(user, false);
    }

    private IssuedTokens issueNewFamily(User user) {
        return issueForFamily(user, UUID.randomUUID());
    }

    private IssuedTokens issueForFamily(User user, UUID familyId) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail());
        String refreshToken = jwtTokenProvider.generateOpaqueRefreshToken();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(jwtProperties.getRefreshTokenTtlDays());
        RefreshToken saved = refreshTokenRepository.save(
                new RefreshToken(user, jwtTokenProvider.hash(refreshToken), familyId, expiresAt));
        // 온보딩 상태(WORK-02)는 아직 구현되지 않아 항상 false를 반환한다.
        return new IssuedTokens(
                user, saved.getId(), accessToken, refreshToken, jwtTokenProvider.accessTokenTtlSeconds(), false);
    }

    public record IssuedTokens(
            User user,
            Long refreshTokenId,
            String accessToken,
            String refreshToken,
            long expiresInSeconds,
            boolean onboardingCompleted) {
    }
}
