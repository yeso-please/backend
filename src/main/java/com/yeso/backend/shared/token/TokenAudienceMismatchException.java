package com.yeso.backend.shared.token;

import com.yeso.backend.shared.exception.DomainException;
import com.yeso.backend.shared.exception.ErrorCode;

/** 초대 token을 공유 엔드포인트에 쓰는 등 용도가 다른 token을 제시했을 때. */
public class TokenAudienceMismatchException extends DomainException {

    public TokenAudienceMismatchException(TokenAudience expected) {
        super(ErrorCode.TOKEN_AUDIENCE_MISMATCH, "이 요청에 사용할 수 없는 종류의 토큰입니다: " + expected);
    }
}
