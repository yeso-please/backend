package com.yeso.backend.profile.application;

import com.yeso.backend.auth.domain.User;
import com.yeso.backend.auth.domain.UserNotFoundException;
import com.yeso.backend.auth.infrastructure.UserRepository;
import com.yeso.backend.attraction.domain.Region;
import com.yeso.backend.profile.domain.DuplicateLikedRegionException;
import com.yeso.backend.profile.domain.DuplicateQuestionAnswerException;
import com.yeso.backend.profile.domain.EmbeddingJob;
import com.yeso.backend.profile.domain.EmbeddingOwnerType;
import com.yeso.backend.profile.domain.InvalidChoiceException;
import com.yeso.backend.profile.domain.InvalidQuestionVersionException;
import com.yeso.backend.profile.domain.InvalidScheduleDensityException;
import com.yeso.backend.profile.domain.LikedTrip;
import com.yeso.backend.profile.domain.MbtiScorer;
import com.yeso.backend.profile.domain.MissingQuestionAnswerException;
import com.yeso.backend.profile.domain.OnboardingAnswer;
import com.yeso.backend.profile.domain.OnboardingProfileTextComposer;
import com.yeso.backend.profile.domain.OnboardingQuestionBank;
import com.yeso.backend.profile.domain.OnboardingRegionNotFoundException;
import com.yeso.backend.profile.domain.OnboardingSubmission;
import com.yeso.backend.profile.domain.ScheduleDensity;
import com.yeso.backend.profile.domain.TooManyExperienceTagsException;
import com.yeso.backend.profile.domain.TooManyLikedRegionsException;
import com.yeso.backend.profile.domain.UnknownTagException;
import com.yeso.backend.profile.infrastructure.EmbeddingJobRepository;
import com.yeso.backend.profile.infrastructure.EmbeddingProperties;
import com.yeso.backend.profile.infrastructure.LikedTripRepository;
import com.yeso.backend.profile.infrastructure.OnboardingAnswerRepository;
import com.yeso.backend.profile.infrastructure.OnboardingSubmissionRepository;
import com.yeso.backend.profile.infrastructure.RegionRepository;
import com.yeso.backend.profile.presentation.AnswerRequest;
import com.yeso.backend.profile.presentation.LikedTripRequest;
import com.yeso.backend.profile.presentation.OnboardingMeResponse;
import com.yeso.backend.profile.presentation.OnboardingQuestionsResponse;
import com.yeso.backend.profile.presentation.OnboardingSubmissionRequest;
import com.yeso.backend.profile.presentation.OnboardingSubmissionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        OnboardingSubmission submission = buildAndPersistSubmission(user, request);
        user.setLatestOnboardingSubmissionId(submission.getId());
        registerEmbeddingJob(submission, EmbeddingOwnerType.USER, userId);
        return submission.getId();
    }

    private OnboardingSubmission buildAndPersistSubmission(User user, OnboardingSubmissionRequest request) {
        if (!OnboardingQuestionBank.QUESTION_VERSION.equals(request.questionVersion())) {
            throw new InvalidQuestionVersionException();
        }
        ScheduleDensity scheduleDensity = parseScheduleDensity(request.scheduleDensity());
        Map<Integer, Integer> answersByNumber = validateAndCollectAnswers(request.answers());
        List<String> experienceTags = validateExperienceTags(request.experienceTags());
        List<String> excludeTags = validateExcludeTags(request.excludeTags());
        List<PreparedLikedTrip> likedTrips = validateLikedTrips(request.likedTrips());

        String mbtiCode = MbtiScorer.score(answersByNumber);
        String profileText = OnboardingProfileTextComposer.compose(
                mbtiCode,
                scheduleDensity,
                experienceTags,
                excludeTags,
                likedTrips.stream()
                        .map(lt -> new OnboardingProfileTextComposer.LikedTripInput(
                                lt.region().getSigCd(), lt.note(), lt.tags()))
                        .toList());

        OnboardingSubmission submission = new OnboardingSubmission(
                user, OnboardingQuestionBank.QUESTION_VERSION, mbtiCode, profileText,
                scheduleDensity, experienceTags, excludeTags);
        submissionRepository.save(submission);

        answersByNumber.forEach((number, choice) ->
                answerRepository.save(new OnboardingAnswer(submission, number, choice)));

        for (PreparedLikedTrip likedTrip : likedTrips) {
            likedTripRepository.save(
                    new LikedTrip(submission, likedTrip.region(), likedTrip.note(), likedTrip.tags()));
        }

        return submission;
    }

    private void registerEmbeddingJob(OnboardingSubmission submission, EmbeddingOwnerType ownerType, Long ownerId) {
        embeddingJobRepository.save(new EmbeddingJob(
                submission, ownerType, ownerId,
                embeddingProperties.getModelVersion(), embeddingProperties.getTemplateVersion()));
        eventPublisher.publishEvent(new OnboardingSubmittedEvent(submission.getId()));
    }

    @Transactional(readOnly = true)
    public OnboardingSubmissionResponse getSubmissionResponse(UUID submissionId) {
        OnboardingSubmission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new IllegalStateException("submission을 찾을 수 없습니다: " + submissionId));
        return OnboardingSubmissionResponse.from(submission);
    }

    @Transactional(readOnly = true)
    public OnboardingMeResponse getMe(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        if (user.getLatestOnboardingSubmissionId() == null) {
            return OnboardingMeResponse.notSubmittedYet();
        }
        OnboardingSubmission submission = submissionRepository.findById(user.getLatestOnboardingSubmissionId())
                .orElseThrow(() -> new IllegalStateException(
                        "latest_onboarding_submission_id가 존재하지 않는 submission을 가리킵니다: "
                                + user.getLatestOnboardingSubmissionId()));
        return OnboardingMeResponse.of(OnboardingSubmissionResponse.from(submission));
    }

    private static ScheduleDensity parseScheduleDensity(String value) {
        try {
            return ScheduleDensity.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidScheduleDensityException();
        }
    }

    private static Map<Integer, Integer> validateAndCollectAnswers(List<AnswerRequest> answers) {
        Map<Integer, Integer> byNumber = new HashMap<>();
        for (AnswerRequest answer : answers) {
            Integer number = answer.questionNumber();
            Integer choice = answer.choice();
            if (number == null || number < 1 || number > OnboardingQuestionBank.QUESTIONS.size()) {
                throw new InvalidChoiceException(number == null ? -1 : number);
            }
            if (byNumber.containsKey(number)) {
                throw new DuplicateQuestionAnswerException(number);
            }
            if (choice == null || (choice != 1 && choice != 2)) {
                throw new InvalidChoiceException(number);
            }
            byNumber.put(number, choice);
        }
        for (int number = 1; number <= OnboardingQuestionBank.QUESTIONS.size(); number++) {
            if (!byNumber.containsKey(number)) {
                throw new MissingQuestionAnswerException(number);
            }
        }
        return byNumber;
    }

    private static List<String> validateExperienceTags(List<String> tags) {
        if (tags.size() > OnboardingQuestionBank.MAX_EXPERIENCE_TAGS) {
            throw new TooManyExperienceTagsException();
        }
        for (String tag : tags) {
            if (!OnboardingQuestionBank.EXPERIENCE_TAGS.contains(tag)) {
                throw new UnknownTagException(tag);
            }
        }
        return tags;
    }

    private static List<String> validateExcludeTags(List<String> tags) {
        for (String tag : tags) {
            if (!OnboardingQuestionBank.EXCLUDE_TAGS.contains(tag)) {
                throw new UnknownTagException(tag);
            }
        }
        return tags;
    }

    private List<PreparedLikedTrip> validateLikedTrips(List<LikedTripRequest> likedTrips) {
        if (likedTrips.size() > OnboardingQuestionBank.MAX_LIKED_TRIPS) {
            throw new TooManyLikedRegionsException();
        }
        Set<String> seenSigCd = new HashSet<>();
        List<PreparedLikedTrip> prepared = new ArrayList<>();
        for (LikedTripRequest request : likedTrips) {
            if (!seenSigCd.add(request.sigCd())) {
                throw new DuplicateLikedRegionException(request.sigCd());
            }
            Region region = regionRepository.findById(request.sigCd())
                    .orElseThrow(() -> new OnboardingRegionNotFoundException(request.sigCd()));
            for (String tag : request.tags()) {
                if (!OnboardingQuestionBank.EXPERIENCE_TAGS.contains(tag)) {
                    throw new UnknownTagException(tag);
                }
            }
            prepared.add(new PreparedLikedTrip(region, request.note(), request.tags()));
        }
        return prepared;
    }

    private record PreparedLikedTrip(Region region, String note, List<String> tags) {
    }
}
