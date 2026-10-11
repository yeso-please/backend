package com.yeso.backend.profile.application.onboarding;

import com.yeso.backend.auth.infrastructure.UserRepository;
import com.yeso.backend.profile.domain.OnboardingQuestionBank;
import com.yeso.backend.profile.infrastructure.OnboardingSubmissionRepository;
import com.yeso.backend.profile.infrastructure.EmbeddingProperties;
import com.yeso.backend.profile.infrastructure.UserTasteVectorRepository;
import com.yeso.backend.shared.embedding.VectorCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 다른 모듈(trip의 지역 정하기 3-7·코스 생성 5-1)이 쓰는 온보딩 조회 계약.
 * profile의 Repository를 직접 쓰지 않는다(docs/conventions/모듈-의존성.md).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OnboardingQueryService {

    private final UserRepository userRepository;
    private final OnboardingSubmissionRepository onboardingSubmissionRepository;
    private final UserTasteVectorRepository userTasteVectorRepository;
    private final EmbeddingProperties embeddingProperties;

    /** 추천 이유의 근거가 되는 최신 설문 답(docs/api/trip.md 추천 이유). 여행 MBTI는 표시용이라 넣지 않는다. */
    public record TasteAnswers(Map<Integer, Integer> travelStyles, List<Integer> travelMotives) {
    }

    /** 최신 설문의 AI Hub 스타일·동기. 설문 전이면 빈 값. */
    public Optional<TasteAnswers> findLatestTasteAnswers(Long userId) {
        return userRepository.findById(userId)
                .map(user -> user.getLatestOnboardingSubmissionId())
                .flatMap(onboardingSubmissionRepository::findById)
                .map(submission -> new TasteAnswers(
                        toIntMap(submission.getTravelStyles()), toIntList(submission.getTravelMotives())));
    }

    // JSONB에서 읽은 값은 실행 시점에 키·원소가 문자열일 수 있어(제네릭 소거) 정수로 맞춘다.
    private static Map<Integer, Integer> toIntMap(Map<?, ?> raw) {
        Map<Integer, Integer> result = new java.util.HashMap<>();
        if (raw != null) {
            raw.forEach((k, v) -> result.put(Integer.valueOf(String.valueOf(k)), Integer.valueOf(String.valueOf(v))));
        }
        return Map.copyOf(result);
    }

    private static List<Integer> toIntList(List<?> raw) {
        return raw == null ? List.of() : raw.stream().map(v -> Integer.valueOf(String.valueOf(v))).toList();
    }

    /** 최신 설문의 일정 밀도({@code RELAXED}·{@code PACKED}). 설문 전이면 빈 값. */
    public Optional<String> findLatestScheduleDensity(Long userId) {
        return userRepository.findById(userId)
                .map(user -> user.getLatestOnboardingSubmissionId())
                .flatMap(onboardingSubmissionRepository::findById)
                .map(submission -> submission.getScheduleDensity().name());
    }

    /** 회원 취향 벡터. 아직 없거나, 저장된 바이트가 기록된 차원과 맞지 않으면 빈 값(코스는 규칙으로 폴백한다). */
    public Optional<float[]> findTasteVector(Long userId) {
        return userTasteVectorRepository.findById(userId)
                .filter(vector -> embeddingProperties.getModelVersion().equals(vector.getModelVersion()))
                .filter(vector -> vector.getDimension() == embeddingProperties.getExpectedDimension())
                .filter(vector -> vector.getTemplateVersion() == OnboardingQuestionBank.AIHUB_TEMPLATE_VERSION)
                .flatMap(vector -> VectorCodec.tryDecode(vector.getEmbedding(), vector.getDimension()));
    }
}
