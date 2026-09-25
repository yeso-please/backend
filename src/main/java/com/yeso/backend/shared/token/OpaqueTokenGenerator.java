package com.yeso.backend.shared.token;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 초대·공유·친구 링크 토큰 공통 생성·해시 유틸. 256bit URL-safe CSPRNG 본문을 만들고
 * DB에는 SHA-256 hash만 저장한다(refresh token과 같은 원칙, docs/adr/0003).
 * 용도 접두사({@link TokenAudience})를 포함한 원문 전체를 해시하므로, 접두사만 바꿔치기해도
 * 다른 용도의 레코드와 겹치지 않는다.
 */
@Component
public class OpaqueTokenGenerator {

    private static final int TOKEN_BYTES = 32; // 256 bit

    private final SecureRandom secureRandom = new SecureRandom();

    public String generate(TokenAudience audience) {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return audience.decorate(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    /** 조회 전에 용도를 먼저 거른다. 통과하면 그대로 돌려줘 호출부가 이어서 해시할 수 있다. */
    public String requireAudience(String token, TokenAudience expected) {
        if (!expected.matches(token)) {
            throw new TokenAudienceMismatchException(expected);
        }
        return token;
    }

    public String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
