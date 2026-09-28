package com.yeso.backend.shared.token;

/**
 * 링크형 opaque token(초대·공유·친구 초대)의 용도. 원문 앞에 짧은 접두사로 새겨 넣어, 다른 용도의 token을
 * 들고 와도 조회 전에 거를 수 있게 한다 — 접두사가 없으면 "존재하지 않는 초대"와 "공유 token을
 * 초대에 잘못 썼다"가 똑같이 404가 되어 클라이언트가 원인을 구분하지 못한다.
 *
 * 접두사는 식별용이며 비밀이 아니다. 추측 방지는 뒤따르는 256bit CSPRNG 본문이 담당한다.
 */
public enum TokenAudience {

    INVITE("iv"),
    SHARE_LINK("sl"),
    SHARE_SESSION("ss"),
    FRIEND_LINK("fl");

    private static final String SEPARATOR = "_";

    private final String prefix;

    TokenAudience(String prefix) {
        this.prefix = prefix;
    }

    public String decorate(String randomPart) {
        return prefix + SEPARATOR + randomPart;
    }

    public boolean matches(String token) {
        return token != null && token.startsWith(prefix + SEPARATOR);
    }
}
