package com.yeso.backend.profile.application.onboarding;

import com.yeso.backend.auth.infrastructure.UserRepository;
import com.yeso.backend.profile.infrastructure.OnboardingSubmissionRepository;
import com.yeso.backend.profile.infrastructure.UserTasteVectorRepository;
import com.yeso.backend.shared.embedding.VectorCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                .flatMap(vector -> VectorCodec.tryDecode(vector.getEmbedding(), vector.getDimension()));
    }
}
