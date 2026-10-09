package com.yeso.backend.profile.application.onboarding;

import com.yeso.backend.auth.domain.User;
import com.yeso.backend.auth.domain.UserNotFoundException;
import com.yeso.backend.auth.infrastructure.UserRepository;
import com.yeso.backend.attraction.domain.Region;
import com.yeso.backend.profile.domain.AiHubProfileTextComposer;
import com.yeso.backend.profile.domain.DuplicateLikedRegionException;
import com.yeso.backend.profile.domain.EmbeddingJob;
import com.yeso.backend.profile.domain.EmbeddingOwnerType;
import com.yeso.backend.profile.domain.InvalidChoiceException;
import com.yeso.backend.profile.domain.InvalidQuestionVersionException;
import com.yeso.backend.profile.domain.InvalidScheduleDensityException;
import com.yeso.backend.profile.domain.InvalidTravelMotiveException;
import com.yeso.backend.profile.domain.InvalidTravelStylesException;
import com.yeso.backend.profile.domain.LikedTrip;
import com.yeso.backend.profile.domain.MbtiScorer;
import com.yeso.backend.profile.domain.MissingQuestionAnswerException;
import com.yeso.backend.profile.domain.OnboardingAnswer;
import com.yeso.backend.profile.domain.OnboardingQuestionBank;
import com.yeso.backend.profile.domain.OnboardingRegionNotFoundException;
import com.yeso.backend.profile.domain.OnboardingSubmission;
import com.yeso.backend.profile.domain.ScheduleDensity;
import com.yeso.backend.profile.domain.TooManyLikedRegionsException;
import com.yeso.backend.profile.domain.UnknownTagException;
import com.yeso.backend.profile.infrastructure.EmbeddingJobRepository;
import com.yeso.backend.profile.infrastructure.EmbeddingProperties;
import com.yeso.backend.profile.infrastructure.LikedTripRepository;
import com.yeso.backend.profile.infrastructure.OnboardingAnswerRepository;
import com.yeso.backend.profile.infrastructure.OnboardingSubmissionRepository;
import com.yeso.backend.attraction.infrastructure.RegionRepository;
import com.yeso.backend.profile.presentation.onboarding.OnboardingMeResponse;
import com.yeso.backend.profile.presentation.onboarding.OnboardingQuestionsResponse;
import com.yeso.backend.profile.presentation.onboarding.OnboardingSubmissionRequest;
import com.yeso.backend.profile.presentation.onboarding.OnboardingSubmissionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class OnboardingService {

    private final UserRepository userRepository;
    private final RegionRepository regionRepository;
    private final OnboardingSubmissionRepository submissionRepository;
    private final OnboardingAnswerRepository answerRepository;
    private final LikedTripRepository likedTripRepository;
    private final EmbeddingJobRepository embeddingJobRepository;
    private final EmbeddingProperties embeddingProperties;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public OnboardingQuestionsResponse getQuestions() {
        return OnboardingQuestionsResponse.current();
    }

    /**
     * @return 새 submission의 id만 반환한다. 응답 DTO는 이 메서드가 반환된 뒤(=트랜잭션이 커밋되고
     * AFTER_COMMIT 임베딩 처리까지 끝난 뒤) {@link #getSubmissionResponse}로 다시 읽어 만든다 —
     * 그렇지 않으면 이 메서드 안에서 만든 응답은 임베딩 결과 반영 전(tasteStatus=PENDING) 스냅샷이 된다.
     */
    public UUID submit(Long userId, OnboardingSubmissionRequest request) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        String version = request.questionVersion();
        if (!OnboardingQuestionBank.QUESTION_VERSION.equals(version)
                && !OnboardingQuestionBank.V1_QUESTION_VERSION.equals(version)) {
            throw new InvalidQuestionVersionException();
        }
        OnboardingSubmission submission = buildAndPersistSubmission(user, version, request);
        user.setLatestOnboardingSubmissionId(submission.getId());
        registerEmbeddingJob(submission, EmbeddingOwnerType.USER, userId);
        return submission.getId();
    }

    private OnboardingSubmission buildAndPersistSubmission(User user, String version, OnboardingSubmissionRequest request) {
        ScheduleDensity scheduleDensity = parseScheduleDensity(request.scheduleDensity());
        Map<Integer, Integer> travelStyles = validateTravelStyles(request.travelStyles());
        List<Integer> travelMotives = validateTravelMotives(request.travelMotives());
        List<String> excludeTags = validateExcludeTags(request.excludeTags());
        List<Region> likedRegions = validateLikedRegions(request.likedRegions());
        // MBTI는 v2에만 있고 표시용이다. 임베딩 문장(profileText)에는 넣지 않는다.
        Map<Integer, Integer> mbtiAnswers = OnboardingQuestionBank.QUESTION_VERSION.equals(version)
                ? validateMbtiAnswers(request.mbtiAnswers())
                : null;
        String mbtiCode = mbtiAnswers == null ? null : MbtiScorer.score(mbtiAnswers);
        String profileText = AiHubProfileTextComposer.compose(
                travelStyles, travelMotives, likedRegions.stream().map(Region::displayName).toList());

        OnboardingSubmission submission = new OnboardingSubmission(
                user, version, mbtiCode, profileText,
                scheduleDensity, List.of(), excludeTags, travelStyles, travelMotives, mbtiAnswers);
        submissionRepository.save(submission);
        travelStyles.forEach((number, value) -> answerRepository.save(new OnboardingAnswer(submission, number, value)));
        likedRegions.forEach(region -> likedTripRepository.save(new LikedTrip(submission, region, null, List.of())));
        return submission;
    }

    private void registerEmbeddingJob(OnboardingSubmission submission, EmbeddingOwnerType ownerType, Long ownerId) {
        embeddingJobRepository.save(new EmbeddingJob(
                submission, ownerType, ownerId,
                embeddingProperties.getModelVersion(), OnboardingQuestionBank.AIHUB_TEMPLATE_VERSION));
        eventPublisher.publishEvent(new OnboardingSubmittedEvent(submission.getId()));
    }

    @Transactional(readOnly = true)
    public OnboardingSubmissionResponse getSubmissionResponse(UUID submissionId) {
        OnboardingSubmission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new IllegalStateException("submission을 찾을 수 없습니다: " + submissionId));
        return response(submission);
    }

    @Transactional(readOnly = true)
    public OnboardingMeResponse getMe(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        if (!user.isOnboardingCompleted()) {
            return OnboardingMeResponse.notSubmittedYet();
        }
        OnboardingSubmission submission = submissionRepository.findById(user.getLatestOnboardingSubmissionId())
                .orElseThrow(() -> new IllegalStateException(
                        "latest_onboarding_submission_id가 존재하지 않는 submission을 가리킵니다: "
                                + user.getLatestOnboardingSubmissionId()));
        return OnboardingMeResponse.of(response(submission));
    }

    private OnboardingSubmissionResponse response(OnboardingSubmission submission) {
        List<String> likedRegions = likedTripRepository.findAllBySubmission_IdOrderByIdAsc(submission.getId()).stream()
                .map(trip -> trip.getRegion().getSigCd())
                .toList();
        return OnboardingSubmissionResponse.from(submission, likedRegions);
    }

    private static ScheduleDensity parseScheduleDensity(String value) {
        try {
            return ScheduleDensity.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidScheduleDensityException();
        }
    }

    /** 1~12번 모두, 각 1 또는 2. 문항 번호 순서로 정렬해 저장한다. */
    private static Map<Integer, Integer> validateMbtiAnswers(Map<Integer, Integer> answers) {
        int count = OnboardingQuestionBank.MBTI_QUESTIONS.size();
        for (Map.Entry<Integer, Integer> answer : answers.entrySet()) {
            Integer number = answer.getKey();
            Integer choice = answer.getValue();
            if (number == null || number < 1 || number > count) {
                throw new InvalidChoiceException(number == null ? -1 : number);
            }
            if (choice == null || (choice != 1 && choice != 2)) {
                throw new InvalidChoiceException(number);
            }
        }
        for (int number = 1; number <= count; number++) {
            if (!answers.containsKey(number)) {
                throw new MissingQuestionAnswerException(number);
            }
        }
        return new TreeMap<>(answers);
    }

    private static Map<Integer, Integer> validateTravelStyles(Map<Integer, Integer> styles) {
        if (!styles.keySet().equals(OnboardingQuestionBank.TRAVEL_STYLE_QUESTIONS.keySet())
                || styles.values().stream().anyMatch(value -> value == null || value < 1 || value > 7)) {
            throw new InvalidTravelStylesException();
        }
        return Map.copyOf(styles);
    }

    private static List<Integer> validateTravelMotives(List<Integer> motives) {
        if (motives.size() > OnboardingQuestionBank.MAX_TRAVEL_MOTIVES
                || new HashSet<>(motives).size() != motives.size()
                || motives.stream().anyMatch(motive -> motive == null
                        || !OnboardingQuestionBank.TRAVEL_MOTIVES.containsKey(motive))) {
            throw new InvalidTravelMotiveException();
        }
        return List.copyOf(motives);
    }

    private List<Region> validateLikedRegions(List<String> likedRegions) {
        if (likedRegions.size() > OnboardingQuestionBank.MAX_LIKED_REGIONS) {
            throw new TooManyLikedRegionsException(OnboardingQuestionBank.MAX_LIKED_REGIONS, "선호 지역");
        }
        Set<String> seenSigCd = new HashSet<>();
        List<Region> regions = new ArrayList<>();
        for (String sigCd : likedRegions) {
            if (sigCd == null || sigCd.isBlank()) {
                throw new OnboardingRegionNotFoundException(String.valueOf(sigCd));
            }
            if (!seenSigCd.add(sigCd)) {
                throw new DuplicateLikedRegionException(sigCd);
            }
            regions.add(regionRepository.findById(sigCd).orElseThrow(() -> new OnboardingRegionNotFoundException(sigCd)));
        }
        return regions;
    }

    private static List<String> validateExcludeTags(List<String> tags) {
        for (String tag : tags) {
            if (!OnboardingQuestionBank.EXCLUDE_TAGS.contains(tag)) {
                throw new UnknownTagException(tag);
            }
        }
        return tags;
    }
}
