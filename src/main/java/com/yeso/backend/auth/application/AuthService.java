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
import com.yeso.backend.auth.presentation.LogoutRequest;
import com.yeso.backend.auth.presentation.RefreshRequest;
import com.yeso.backend.auth.presentation.SignupRequest;
import com.yeso.backend.auth.presentation.TokenResponse;
import com.yeso.backend.auth.presentation.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;

    public TokenResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException(request.email());
        }
        User user = new User(request.email(), passwordEncoder.encode(request.password()), request.nickname());
        userRepository.save(user);
        return issueTokens(user);
    }

    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .filter(u -> u.getPasswordHash() != null)
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return issueTokens(user);
    }

    public TokenResponse refresh(RefreshRequest request) {
        Long userIdFromToken = jwtTokenProvider.parseRefreshTokenUserId(request.refreshToken());
        String tokenHash = jwtTokenProvider.hash(request.refreshToken());
        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash)
                .filter(rt -> !rt.isRevoked())
                .filter(rt -> rt.getExpiresAt().isAfter(LocalDateTime.now()))
                .filter(rt -> rt.getUser().getId().equals(userIdFromToken))
                .orElseThrow(InvalidRefreshTokenException::new);
        stored.setRevoked(true);
        return issueTokens(stored.getUser());
    }

    public void logout(LogoutRequest request) {
        String tokenHash = jwtTokenProvider.hash(request.refreshToken());
        refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(rt -> rt.setRevoked(true));
    }

    @Transactional(readOnly = true)
    public UserResponse getMe(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        return UserResponse.from(user);
    }

    private TokenResponse issueTokens(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(jwtProperties.getRefreshTokenTtlDays());
        refreshTokenRepository.save(new RefreshToken(user, jwtTokenProvider.hash(refreshToken), expiresAt));
        return new TokenResponse(accessToken, refreshToken, "Bearer", jwtTokenProvider.accessTokenTtlSeconds());
    }
}
