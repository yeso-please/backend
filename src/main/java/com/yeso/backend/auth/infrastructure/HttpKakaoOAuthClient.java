package com.yeso.backend.auth.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yeso.backend.auth.domain.KakaoInvalidCodeException;
import com.yeso.backend.auth.domain.KakaoNotConfiguredException;
import com.yeso.backend.auth.domain.KakaoUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

/**
 * 카카오 로그인 REST API 연동. 인가 코드·카카오 토큰·client secret과 응답 전문은 로그에 남기지 않는다 —
 * 실패하면 종류(상태 코드, 카카오 오류 코드)만 남긴다.
 *
 * <p>토큰 교환은 재시도하지 않는다(코드는 한 번만 쓸 수 있어 재시도하면 {@code invalid_grant}가 된다).
 * 사용자 정보 조회는 timeout·5xx일 때 1회까지 재시도한다.
 */
@Slf4j
@Component
public class HttpKakaoOAuthClient implements KakaoOAuthClient {

    private static final String INVALID_GRANT = "invalid_grant";
    private static final int FETCH_USER_ATTEMPTS = 2;

    private final KakaoOAuthProperties properties;
    private final RestClient authClient;
    private final RestClient apiClient;

    public HttpKakaoOAuthClient(KakaoOAuthProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()));
        factory.setReadTimeout(Duration.ofMillis(properties.getTimeoutMillis()));
        this.authClient = RestClient.builder().baseUrl(properties.getAuthBaseUrl()).requestFactory(factory).build();
        this.apiClient = RestClient.builder().baseUrl(properties.getApiBaseUrl()).requestFactory(factory).build();
    }

    @Override
    public String exchangeCode(String code, String redirectUri) {
        if (!properties.hasCredentials()) {
            log.warn("Kakao login client id/secret is not configured");
            throw new KakaoNotConfiguredException();
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        form.add("redirect_uri", redirectUri);
        form.add("code", code);
        try {
            TokenResponse response = authClient.post()
                    .uri("/oauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
            if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
                throw new IllegalArgumentException("missing access_token");
            }
            return response.accessToken();
        } catch (RestClientResponseException e) {
            TokenError error = tokenError(e);
            log.warn("Kakao token exchange returned status={} error={} errorCode={}",
                    e.getStatusCode().value(), error.error(), error.errorCode());
            if (e.getStatusCode().value() == 400 && INVALID_GRANT.equals(error.error())) {
                throw new KakaoInvalidCodeException();
            }
            throw new KakaoUnavailableException();
        } catch (ResourceAccessException e) {
            log.warn("Kakao token exchange unreachable/timeout: {}", e.getClass().getSimpleName());
            throw new KakaoUnavailableException();
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("Kakao token exchange malformed response: {}", e.getClass().getSimpleName());
            throw new KakaoUnavailableException();
        }
    }

    @Override
    public KakaoUser fetchUser(String accessToken) {
        for (int attempt = 1; ; attempt++) {
            try {
                return requestUser(accessToken);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                log.warn("Kakao user info returned status={} attempt={}", status, attempt);
                if (status < 500 || attempt >= FETCH_USER_ATTEMPTS) {
                    throw new KakaoUnavailableException();
                }
            } catch (ResourceAccessException e) {
                log.warn("Kakao user info unreachable/timeout: {} attempt={}", e.getClass().getSimpleName(), attempt);
                if (attempt >= FETCH_USER_ATTEMPTS) {
                    throw new KakaoUnavailableException();
                }
            } catch (RestClientException | IllegalArgumentException e) {
                log.warn("Kakao user info malformed response: {}", e.getClass().getSimpleName());
                throw new KakaoUnavailableException();
            }
        }
    }

    private KakaoUser requestUser(String accessToken) {
        UserResponse response = apiClient.get()
                .uri("/v2/user/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(UserResponse.class);
        if (response == null || response.id() == null) {
            throw new IllegalArgumentException("missing id");
        }
        String nickname = response.kakaoAccount() == null || response.kakaoAccount().profile() == null
                ? null
                : response.kakaoAccount().profile().nickname();
        return new KakaoUser(String.valueOf(response.id()), nickname);
    }

    private static TokenError tokenError(RestClientResponseException e) {
        try {
            TokenError error = e.getResponseBodyAs(TokenError.class);
            return error == null ? TokenError.EMPTY : error;
        } catch (RuntimeException ignored) {
            return TokenError.EMPTY;
        }
    }

    private record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }

    private record TokenError(String error, @JsonProperty("error_code") String errorCode) {
        static final TokenError EMPTY = new TokenError(null, null);
    }

    private record UserResponse(Long id, @JsonProperty("kakao_account") KakaoAccount kakaoAccount) {
    }

    private record KakaoAccount(Profile profile) {
    }

    private record Profile(String nickname) {
    }
}
