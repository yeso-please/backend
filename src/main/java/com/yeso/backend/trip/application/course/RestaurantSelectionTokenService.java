package com.yeso.backend.trip.application.course;

import com.yeso.backend.trip.domain.RestaurantSelectionInvalidException;
import com.yeso.backend.trip.domain.RestaurantSnapshot;
import com.yeso.backend.trip.infrastructure.RestaurantProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * 식당 {@code selectionToken}(docs/api/trip.md 5-5). 식당 스냅샷 전체를 서버 비밀키로 서명해 5-5·5-6 결과에 싣고,
 * 5-3 {@code SET_RESTAURANT}가 돌려받으면 검증한 뒤 스냅샷을 그대로 쓴다. 서버에 저장하지 않는다.
 *
 * <p>형식: {@code rs_} + {@code base64url(header).base64url(payload).base64url(HMAC-SHA256(header.payload))}.
 * 발급 후 30분, 발급한 여행·식사 슬롯에서만 유효하다.
 */
@Service
public class RestaurantSelectionTokenService {

    static final String PREFIX = "rs_";
    static final Duration TTL = Duration.ofMinutes(30);

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_SECRET_BYTES = 32;
    private static final Header HEADER = new Header("HS256", "RS");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final RestaurantProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public RestaurantSelectionTokenService(RestaurantProperties properties, JsonMapper jsonMapper, Clock clock) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    public String issue(Long tripId, String itemId, RestaurantSnapshot snapshot) {
        long issuedAt = clock.instant().getEpochSecond();
        Payload payload = Payload.of(tripId, itemId, snapshot, issuedAt, issuedAt + TTL.toSeconds());
        String signingInput = encode(jsonMapper.writeValueAsBytes(HEADER)) + "." + encode(jsonMapper.writeValueAsBytes(payload));
        return PREFIX + signingInput + "." + encode(sign(signingInput));
    }

    /**
     * 서명·만료·여행·슬롯을 검증하고 토큰 안의 스냅샷을 돌려준다. 어느 검사든 실패하면
     * {@link RestaurantSelectionInvalidException}이다.
     */
    public RestaurantSnapshot verify(String token, Long tripId, String itemId) {
        if (token == null || !token.startsWith(PREFIX)) {
            throw new RestaurantSelectionInvalidException();
        }
        String[] parts = token.substring(PREFIX.length()).split("\\.", -1);
        if (parts.length != 3) {
            throw new RestaurantSelectionInvalidException();
        }
        Payload payload;
        try {
            byte[] expected = sign(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected, DECODER.decode(parts[2]))) {
                throw new RestaurantSelectionInvalidException();
            }
            Header header = jsonMapper.readValue(DECODER.decode(parts[0]), Header.class);
            if (!HEADER.equals(header)) {
                throw new RestaurantSelectionInvalidException();
            }
            payload = jsonMapper.readValue(DECODER.decode(parts[1]), Payload.class);
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException e) {
            throw new RestaurantSelectionInvalidException();
        }
        if (clock.instant().getEpochSecond() >= payload.exp()
                || !tripId.equals(payload.tripId()) || !itemId.equals(payload.itemId())) {
            throw new RestaurantSelectionInvalidException();
        }
        return payload.snapshot();
    }

    private byte[] sign(String signingInput) {
        byte[] secret = properties.getSelectionSecret() == null
                ? new byte[0]
                : properties.getSelectionSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("course.restaurant.selection-secret 이 비어 있거나 32 byte 미만입니다. "
                    + "./config/application-secret.yaml 또는 환경변수 COURSE_RESTAURANT_SELECTION_SECRET 로 주입하세요.");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256을 사용할 수 없습니다.", e);
        }
    }

    private static String encode(byte[] bytes) {
        return ENCODER.encodeToString(bytes);
    }

    record Header(String alg, String typ) {
    }

    /** 명세 5-5의 payload 필드. {@code source}는 스냅샷의 provider다. {@code iat}·{@code exp}는 epoch 초. */
    record Payload(
            Long tripId, String itemId, String source, String externalId, String name, String category,
            String address, String roadAddress, Double lat, Double lng, String phone, String placeUrl,
            String imageUrl, String representativeMenu, List<String> evidenceLabels,
            List<RestaurantSnapshot.Source> sources, long iat, long exp) {

        static Payload of(Long tripId, String itemId, RestaurantSnapshot s, long iat, long exp) {
            return new Payload(tripId, itemId, s.provider(), s.externalId(), s.name(), s.category(), s.address(),
                    s.roadAddress(), s.lat(), s.lng(), s.phone(), s.placeUrl(), s.imageUrl(), s.representativeMenu(),
                    s.evidenceLabels(), s.sources(), iat, exp);
        }

        RestaurantSnapshot snapshot() {
            return new RestaurantSnapshot(source, externalId, name, category, address, roadAddress, lat, lng, phone,
                    placeUrl, imageUrl, representativeMenu, evidenceLabels, sources);
        }
    }
}
