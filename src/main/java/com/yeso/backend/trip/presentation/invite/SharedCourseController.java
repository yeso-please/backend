package com.yeso.backend.trip.presentation.invite;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import com.yeso.backend.trip.application.invite.ShareLinkService;
import com.yeso.backend.trip.infrastructure.ShareSessionCookieFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Tag(name = "4. 초대·공유", description = "docs/api/trip.md §4")
@RestController
@RequiredArgsConstructor
public class SharedCourseController {

    private final ShareLinkService shareLinkService;
    private final ShareSessionCookieFactory cookieFactory;

    /**
     * URL의 원문 token을 HttpOnly cookie로 교환하고 token 없는 URL로 303 redirect한다 —
     * 브라우저 히스토리·referrer·서버 접근 로그에 원문이 남지 않는다.
     */
    @Operation(summary = "4-12 공유 링크 열기")
    @SecurityRequirements()
    @GetMapping("/api/shared/courses/{token}")
    public ResponseEntity<Void> open(@PathVariable String token) {
        String sessionToken = shareLinkService.openAndIssueSession(token);
        ResponseCookie cookie = cookieFactory.create(sessionToken);
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .location(URI.create("/api/shared/courses"))
                .build();
    }

    @Operation(summary = "4-13 공유 코스 조회")
    @SecurityRequirements()
    @GetMapping("/api/shared/courses")
    public SharedCourseViewResponse view(
            @CookieValue(value = ShareSessionCookieFactory.COOKIE_NAME, required = false) String shareSession) {
        return shareLinkService.view(shareSession);
    }
}
