package com.yeso.backend.trip.application.context;

import com.yeso.backend.attraction.application.region.RegionQueryService;
import com.yeso.backend.attraction.presentation.region.RegionCardResponse;
import com.yeso.backend.profile.application.onboarding.OnboardingQueryService;
import com.yeso.backend.profile.application.onboarding.TravelPersonaCatalog;
import com.yeso.backend.profile.application.onboarding.TravelPersonaCatalog.Persona;
import com.yeso.backend.shared.ai.AiTextClient;
import com.yeso.backend.shared.ai.AiTextClient.Landmark;
import com.yeso.backend.shared.ai.AiTextClient.RegionMatch;
import com.yeso.backend.shared.ai.AiTextClient.RegionMatchRequest;
import com.yeso.backend.trip.infrastructure.RegionMatchTextRepository;
import com.yeso.backend.trip.presentation.context.RegionMatchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 7-6 B4 "이 지역이 나와 맞는 이유"(D13-3). 요청자의 여행 성향(MBTI 4글자 → 성향 이름) × 지역 근거(승인된 태그·한 줄 소개,
 * 대표 관광지)로 ai가 2~3문장을 쓴다. 표시용이며 추천 계산에는 쓰지 않는다. 설문 답·닉네임은 보내지 않는다.
 *
 * <p>지역 × 16유형 조합이라 한 번 만든 LLM 문장은 {@code region_match_texts}에 기억하고, 근거가 바뀌면(해시) 다시 만든다.
 * ai가 없거나 실패하면 규칙 문장을 주고 기억하지 않는다(다음 요청에서 다시 시도).
 */
@Service
@RequiredArgsConstructor
public class RegionMatchService {

    static final String PROMPT_VERSION = "region-match-v1";
    private static final int MAX_LANDMARKS = 3;

    private final RegionQueryService regionQueryService;
    private final OnboardingQueryService onboardingQueryService;
    private final TravelPersonaCatalog personaCatalog;
    private final RegionMatchTextRepository repository;
    private final AiTextClient aiTextClient;

    public RegionMatchResponse match(Long userId, String sigCd) {
        RegionCardResponse card = regionQueryService.regionCard(sigCd);
        Optional<String> mbti = onboardingQueryService.findLatestMbtiCode(userId);
        Optional<Persona> persona = mbti.flatMap(personaCatalog::find);
        if (persona.isEmpty()) {
            // v1 설문·MBTI 전이면 성향 문장을 만들지 않는다.
            return new RegionMatchResponse(card.sigCd(), null, null, null, null, false);
        }
        Persona p = persona.get();
        List<Landmark> landmarks = card.landmarks().stream().limit(MAX_LANDMARKS)
                .map(landmark -> new Landmark(landmark.name(), landmark.oneLine(), null))
                .toList();
        String regionName = card.province() + " " + card.city();
        String hash = hash(p, regionName, card.tagline(), card.tags(), landmarks);

        Optional<RegionMatchTextRepository.Cached> cached = repository.find(sigCd, p.code());
        if (cached.isPresent() && cached.get().sourceHash().equals(hash)) {
            return response(card, p, cached.get().text(), cached.get().textSource());
        }
        Optional<RegionMatch> generated = aiTextClient.matchRegion(new RegionMatchRequest(
                "region-" + sigCd + "-" + p.code(), p.name(), p.oneLiner(), likes(p), regionName, card.tagline(), card.tags(),
                landmarks));
        if (generated.isPresent() && "llm".equals(generated.get().source()) && notBlank(generated.get().text())) {
            String text = generated.get().text().strip();
            repository.save(sigCd, p.code(), text, "LLM", hash, generated.get().generatorModel(), PROMPT_VERSION);
            return response(card, p, text, "LLM");
        }
        return response(card, p, ruleText(p, card.city(), landmarks, card.tags()), "RULE");
    }

    private static RegionMatchResponse response(RegionCardResponse card, Persona persona, String text, String source) {
        return new RegionMatchResponse(card.sigCd(), persona.code(), persona.name(), text, source, "LLM".equals(source));
    }

    /** ai 없이 만드는 문장. 근거(대표 관광지·태그)에 있는 것만 쓴다. */
    static String ruleText(Persona persona, String city, List<Landmark> landmarks, List<String> tags) {
        if (!landmarks.isEmpty()) {
            String names = String.join(", ", landmarks.stream().map(Landmark::name).limit(2).toList());
            return persona.name() + "인 당신에게 " + city + "의 " + names + "을(를) 추천해요.";
        }
        if (!tags.isEmpty()) {
            return persona.name() + "인 당신에게 #" + String.join(" #", tags) + " 여행지 " + city + "을(를) 추천해요.";
        }
        return persona.name() + "인 당신의 다음 여행지로 " + city + "은(는) 어떨까요?";
    }

    private static List<String> likes(Persona persona) {
        List<String> likes = new ArrayList<>(persona.places());
        likes.addAll(persona.activities());
        return likes;
    }

    private static String hash(Persona persona, String regionName, String tagline, List<String> tags, List<Landmark> landmarks) {
        StringBuilder source = new StringBuilder(PROMPT_VERSION).append('|').append(persona.code()).append('|')
                .append(persona.name()).append('|').append(regionName).append('|').append(tagline).append('|').append(tags);
        landmarks.forEach(landmark -> source.append('|').append(landmark.name()).append(':').append(landmark.oneLine()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
