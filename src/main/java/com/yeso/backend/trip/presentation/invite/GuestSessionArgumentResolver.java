package com.yeso.backend.trip.presentation.invite;

import com.yeso.backend.trip.application.invite.InviteService;
import com.yeso.backend.trip.domain.GuestSessionInvalidException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 회원 JWT와 별개로 Authorization 헤더의 guest session opaque token을 해석한다.
 * JwtAuthenticationFilter는 이 값을 JWT로 파싱하려다 실패해 조용히 무시하므로(필터 자체 문서 참고)
 * SecurityContext는 비어 있는 채로 남고, 이 resolver가 같은 헤더를 독립적으로 다시 읽는다.
 */
@Component
@RequiredArgsConstructor
public class GuestSessionArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String HEADER_NAME = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final InviteService inviteService;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentGuestParticipant.class)
                && Long.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String header = webRequest.getHeader(HEADER_NAME);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new GuestSessionInvalidException();
        }
        String token = header.substring(BEARER_PREFIX.length());
        return inviteService.resolveGuestParticipantId(token);
    }
}
