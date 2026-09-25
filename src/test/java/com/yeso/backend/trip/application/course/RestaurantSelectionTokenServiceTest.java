package com.yeso.backend.trip.application.course;

import com.yeso.backend.trip.domain.RestaurantSelectionInvalidException;
import com.yeso.backend.trip.domain.RestaurantSnapshot;
import com.yeso.backend.trip.infrastructure.RestaurantProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestaurantSelectionTokenServiceTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-10-01T01:00:00Z");
    private static final String SECRET = "unit-test-restaurant-selection-secret-0123456789";

    private final RestaurantSnapshot snapshot = new RestaurantSnapshot(
            "TOUR_API", "2871024", "황남맷돌순두부", "한식", "경북 경주시 황남동", "경북 경주시 포석로",
            35.83, 129.21, "054-000-0000", null, "https://example.com/a.jpg", "순두부찌개",
            List.of("한국관광공사 등록 음식점"),
            List.of(new RestaurantSnapshot.Source("한국관광공사 TourAPI", "https://example.com",
                    LocalDateTime.of(2026, 9, 30, 0, 0))));

    private RestaurantProperties properties;
    private JsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        properties = new RestaurantProperties();
        properties.setSelectionSecret(SECRET);
        jsonMapper = JsonMapper.builder().build();
    }

    private RestaurantSelectionTokenService serviceAt(Instant now) {
        return new RestaurantSelectionTokenService(properties, jsonMapper, Clock.fixed(now, ZoneId.of("Asia/Seoul")));
    }

    private String issue() {
        return serviceAt(ISSUED_AT).issue(42L, "m-31", snapshot);
    }

    @Test
    @DisplayName("발급한 토큰은 rs_로 시작하는 세 조각이고, 같은 여행·슬롯에서 검증하면 스냅샷을 그대로 돌려준다")
    void issueAndVerify_roundTripsSnapshot() {
        String token = issue();

        assertThat(token).startsWith("rs_");
        assertThat(token.substring(3).split("\\.")).hasSize(3);
        assertThat(serviceAt(ISSUED_AT).verify(token, 42L, "m-31")).isEqualTo(snapshot);
    }

    @Test
    @DisplayName("발급 후 30분 직전까지는 유효하고 30분이 되면 만료다")
    void verify_expiresAfterThirtyMinutes() {
        String token = issue();

        assertThat(serviceAt(ISSUED_AT.plus(Duration.ofMinutes(30)).minusSeconds(1)).verify(token, 42L, "m-31"))
                .isEqualTo(snapshot);
        assertThatThrownBy(() -> serviceAt(ISSUED_AT.plus(Duration.ofMinutes(30))).verify(token, 42L, "m-31"))
                .isInstanceOf(RestaurantSelectionInvalidException.class);
    }

    @Test
    @DisplayName("다른 여행이나 다른 식사 슬롯에 쓰면 거부한다")
    void verify_otherTripOrSlot_isRejected() {
        String token = issue();

        assertThatThrownBy(() -> serviceAt(ISSUED_AT).verify(token, 43L, "m-31"))
                .isInstanceOf(RestaurantSelectionInvalidException.class);
        assertThatThrownBy(() -> serviceAt(ISSUED_AT).verify(token, 42L, "m-32"))
                .isInstanceOf(RestaurantSelectionInvalidException.class);
    }

    @Test
    @DisplayName("payload를 바꾸면 서명이 맞지 않아 거부한다")
    void verify_tamperedPayload_isRejected() {
        String[] parts = issue().substring(3).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("황남맷돌순두부", "가짜식당");
        String tampered = "rs_" + parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        assertThatThrownBy(() -> serviceAt(ISSUED_AT).verify(tampered, 42L, "m-31"))
                .isInstanceOf(RestaurantSelectionInvalidException.class);
    }

    @Test
    @DisplayName("다른 키로 서명한 토큰은 거부한다")
    void verify_otherSecret_isRejected() {
        String token = issue();
        properties.setSelectionSecret("another-secret-that-is-also-long-enough-0123456789");

        assertThatThrownBy(() -> serviceAt(ISSUED_AT).verify(token, 42L, "m-31"))
                .isInstanceOf(RestaurantSelectionInvalidException.class);
    }

    @Test
    @DisplayName("접두사·조각 수·base64·JSON 형식이 틀리면 거부한다")
    void verify_malformedToken_isRejected() {
        String token = issue();
        List<String> malformed = List.of(
                token.substring(3),
                "sl_" + token.substring(3),
                "rs_only.two",
                "rs_a.b.c.d",
                "rs_!!!.???.***",
                "rs_" + Base64.getUrlEncoder().encodeToString("{}".getBytes()) + ".e30.e30",
                "");

        for (String candidate : malformed) {
            assertThatThrownBy(() -> serviceAt(ISSUED_AT).verify(candidate, 42L, "m-31"))
                    .as(candidate)
                    .isInstanceOf(RestaurantSelectionInvalidException.class);
        }
        assertThatThrownBy(() -> serviceAt(ISSUED_AT).verify(null, 42L, "m-31"))
                .isInstanceOf(RestaurantSelectionInvalidException.class);
    }

    @Test
    @DisplayName("서명 키가 비어 있거나 32 byte 미만이면 발급하지 않는다")
    void issue_withoutUsableSecret_fails() {
        properties.setSelectionSecret("");
        assertThatThrownBy(this::issue).isInstanceOf(IllegalStateException.class);

        properties.setSelectionSecret("too-short");
        assertThatThrownBy(this::issue).isInstanceOf(IllegalStateException.class);
    }
}
