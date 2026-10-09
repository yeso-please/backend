package com.yeso.backend.auth.application;

import com.yeso.backend.auth.domain.DuplicateEmailException;
import com.yeso.backend.auth.domain.InvalidCredentialsException;
import com.yeso.backend.auth.domain.InvalidRefreshTokenException;
import com.yeso.backend.auth.domain.RefreshToken;
import com.yeso.backend.auth.domain.SocialAccount;
import com.yeso.backend.auth.domain.SocialProvider;
import com.yeso.backend.auth.domain.User;
import com.yeso.backend.auth.domain.UserNotFoundException;
import com.yeso.backend.auth.infrastructure.JwtProperties;
import com.yeso.backend.auth.infrastructure.JwtTokenProvider;
import com.yeso.backend.auth.infrastructure.RefreshTokenRepository;
import com.yeso.backend.auth.infrastructure.SocialAccountRepository;
import com.yeso.backend.auth.infrastructure.UserRepository;
import com.yeso.backend.auth.presentation.LoginRequest;
import com.yeso.backend.auth.presentation.SignupRequest;
import com.yeso.backend.auth.presentation.UserMeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private static final int NICKNAME_MAX_LENGTH = 30;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SocialAccountRepository socialAccountRepository;
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
        log.info("User signed up userId={} method=EMAIL", user.getId());
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
     * 소셜 회원번호로 회원을 찾아 로그인하고, 없으면 소셜 전용 회원(이메일·비밀번호 없음)과 연동 계정을 만든다
     * (docs/api/auth.md 1-6). 기존 회원의 닉네임은 덮어쓰지 않는다.
     *
     * <p>같은 회원번호의 첫 로그인이 동시에 들어오면 늦은 쪽은 {@code uq_social_provider_user} 위반으로
     * {@code DataIntegrityViolationException}이 난다. 이 트랜잭션은 롤백되므로 호출자가 새 트랜잭션에서 다시 부른다.
     */
    public SocialLogin loginWithSocialAccount(SocialProvider provider, String providerUserId, String nickname) {
        Optional<SocialAccount> existing =
                socialAccountRepository.findByProviderAndProviderUserId(provider, providerUserId);
        if (existing.isPresent()) {
            return new SocialLogin(issueNewFamily(existing.get().getUser()), false);
        }
        User user = userRepository.save(new User(null, null, socialNickname(nickname)));
        socialAccountRepository.saveAndFlush(new SocialAccount(user, provider, providerUserId));
        log.info("User signed up userId={} method={}", user.getId(), provider);
        return new SocialLogin(issueNewFamily(user), true);
    }

    /**
     * refresh row를 lock한 뒤 판정한다: 폐기된(이미 rotate된) 토큰이 다시 제시되면 탈취로 간주해
     * 같은 family를 통째로 무효화하고, 동시에 들어온 나머지 요청은 이 잠금 때문에 순서대로만
     * 처리돼 정확히 하나만 성공한다(docs/adr/0003).
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
        return UserMeResponse.of(user, user.isOnboardingCompleted(), loginMethods(user));
    }

    private List<String> loginMethods(User user) {
        List<String> methods = new ArrayList<>();
        if (user.getPasswordHash() != null) {
            methods.add("EMAIL");
        }
        socialAccountRepository.findProvidersByUserId(user.getId()).forEach(provider -> methods.add(provider.name()));
        return methods;
    }

    /** 소셜 닉네임을 trim해 30자(code point)로 자른다. 비어 있으면 {@code 여행자0000} 형식 기본값을 준다. */
    static String socialNickname(String nickname) {
        String trimmed = nickname == null ? "" : nickname.strip();
        if (trimmed.isEmpty()) {
            return "여행자%04d".formatted(ThreadLocalRandom.current().nextInt(10_000));
        }
        if (trimmed.codePointCount(0, trimmed.length()) <= NICKNAME_MAX_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, NICKNAME_MAX_LENGTH)).strip();
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
        return new IssuedTokens(
                user, saved.getId(), accessToken, refreshToken, jwtTokenProvider.accessTokenTtlSeconds(),
                user.isOnboardingCompleted());
    }

    public record SocialLogin(IssuedTokens tokens, boolean newUser) {
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
