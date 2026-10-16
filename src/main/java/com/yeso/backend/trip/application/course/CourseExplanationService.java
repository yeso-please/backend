package com.yeso.backend.trip.application.course;

import com.yeso.backend.attraction.application.region.CourseMaterialService;
import com.yeso.backend.attraction.application.region.CourseMaterialService.PlaceText;
import com.yeso.backend.attraction.domain.Region;
import com.yeso.backend.shared.ai.AiTextClient;
import com.yeso.backend.shared.ai.AiTextClient.CourseExplanation;
import com.yeso.backend.shared.ai.AiTextClient.CourseExplanationRequest;
import com.yeso.backend.shared.ai.AiTextClient.Place;
import com.yeso.backend.trip.domain.CourseItem;
import com.yeso.backend.trip.domain.CourseTitleSource;
import com.yeso.backend.trip.domain.TripNotFoundException;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.infrastructure.CourseItemRepository;
import com.yeso.backend.trip.infrastructure.TripPlanRepository;
import com.yeso.backend.trip.presentation.course.CourseResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 코스를 만든 직후 ai {@code /explanations}로 제목·소개글·장소별 이유를 받아 붙인다(5-1, D16).
 *
 * <p>LLM은 수 초가 걸리므로 코스 생성 트랜잭션이 끝난 뒤, DB 연결을 잡지 않은 채 부른다. 받은 문장은 여행 버전을 올리지 않고
 * 저장하며, 그사이 다른 참여자가 코스를 바꿨으면(버전이 다르면) 버린다. ai가 없거나 실패하면 규칙 문장 그대로다.
 * 문장은 표시용이고 추천 계산에 쓰지 않는다. 관광지 공개 데이터(이름·분류·설명)만 보낸다.
 */
@Slf4j
@Service
public class CourseExplanationService {

    private static final int REASON_MAX = 200;

    private final TripPlanRepository tripPlanRepository;
    private final CourseItemRepository courseItemRepository;
    private final CourseMaterialService courseMaterialService;
    private final CourseService courseService;
    private final AiTextClient aiTextClient;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate write;

    public CourseExplanationService(
            TripPlanRepository tripPlanRepository, CourseItemRepository courseItemRepository,
            CourseMaterialService courseMaterialService, CourseService courseService, AiTextClient aiTextClient,
            PlatformTransactionManager transactionManager) {
        this.tripPlanRepository = tripPlanRepository;
        this.courseItemRepository = courseItemRepository;
        this.courseMaterialService = courseMaterialService;
        this.courseService = courseService;
        this.aiTextClient = aiTextClient;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.write = new TransactionTemplate(transactionManager);
    }

    /**
     * @param generated 방금 만든 코스 응답(5-1)
     * @return 문장을 붙인 응답. 붙이지 못했으면 {@code generated} 그대로
     */
    public CourseResponse explain(Long userId, CourseResponse generated) {
        CourseExplanationRequest request = readOnly.execute(status -> request(generated.tripId()));
        if (request == null || request.places().isEmpty()) {
            return generated;
        }
        Optional<CourseExplanation> explanation = aiTextClient.explainCourse(request);
        if (explanation.isEmpty()) {
            return generated;
        }
        CourseResponse applied = write.execute(status -> apply(userId, generated, explanation.get()));
        return applied == null ? generated : withGenerationWarnings(applied, generated);
    }

    private CourseExplanationRequest request(Long tripId) {
        TripPlan trip = tripPlanRepository.findById(tripId).orElseThrow(() -> new TripNotFoundException(tripId));
        Region region = trip.getRegion();
        if (region == null) {
            return null;
        }
        List<CourseItem> attractions = courseItemRepository.findByTripPlanIdOrderByDayIndexAscOrderIndexAsc(tripId).stream()
                .filter(item -> !item.isMeal()).toList();
        Map<Long, PlaceText> texts = courseMaterialService.findPlaceTexts(attractions.stream().map(CourseItem::getAttractionId).toList());
        String regionName = region.getProvince() + " " + region.getCity();
        List<Place> places = new ArrayList<>();
        int order = 0;
        int day = -1;
        for (CourseItem item : attractions) {
            PlaceText text = texts.get(item.getAttractionId());
            if (text == null) {
                continue;
            }
            order = item.getDayIndex() == day ? order + 1 : 1;
            day = item.getDayIndex();
            places.add(new Place(item.itemId(), text.name(), text.lclsSystm1(), text.lclsSystm2(), text.lclsSystm3(),
                    regionName, text.description() == null ? "" : text.description(), item.getDayIndex() + 1, order, List.of()));
        }
        return new CourseExplanationRequest("course-" + tripId, regionName, trip.getNights() + 1, places);
    }

    private CourseResponse apply(Long userId, CourseResponse generated, CourseExplanation explanation) {
        boolean llmTitle = "llm".equals(explanation.titleSource()) && notBlank(explanation.title());
        int updated = tripPlanRepository.applyCourseText(generated.tripId(), generated.version(),
                llmTitle ? explanation.title().strip() : generated.title(),
                llmTitle ? CourseTitleSource.LLM : CourseTitleSource.RULE,
                notBlank(explanation.intro()) ? explanation.intro().strip() : null,
                notBlank(explanation.intro()) ? ("llm".equals(explanation.introSource()) ? "LLM" : "RULE") : null);
        if (updated == 0) {
            log.info("Course changed before explanation arrived; dropped tripId={}", generated.tripId());
            return null;
        }
        Map<String, AiTextClient.Reason> reasons = explanation.reasons() == null ? Map.of()
                : explanation.reasons().stream().collect(Collectors.toMap(AiTextClient.Reason::id, Function.identity(), (a, b) -> a));
        for (CourseItem item : courseItemRepository.findByTripPlanIdOrderByDayIndexAscOrderIndexAsc(generated.tripId())) {
            AiTextClient.Reason reason = reasons.get(item.itemId());
            if (item.isMeal() || reason == null) {
                continue;
            }
            // ai 규칙 문장보다 백엔드의 근거 기반 이유(#84)가 낫다. LLM 문장만 바꾼다.
            String text = "llm".equals(reason.source()) && notBlank(reason.reason()) ? truncate(reason.reason()) : null;
            item.applyExplanation(text, notBlank(reason.personalReason()) ? truncate(reason.personalReason()) : null);
        }
        courseItemRepository.flush();
        TripPlan trip = tripPlanRepository.findById(generated.tripId()).orElseThrow(() -> new TripNotFoundException(generated.tripId()));
        return courseService.participantView(trip, userId);
    }

    /** 생성 응답에만 있는 경고(DENSITY_TARGET_NOT_MET)를 유지한다. */
    private static CourseResponse withGenerationWarnings(CourseResponse applied, CourseResponse generated) {
        return new CourseResponse(applied.tripId(), applied.version(), applied.myRole(), applied.regionSigCd(),
                applied.regionName(), applied.startDate(), applied.endDate(), applied.scheduleDensity(), applied.title(),
                applied.titleSource(), applied.intro(), applied.introSource(), applied.aiGenerated(),
                applied.recommendationMode(), applied.tasteBasis(), applied.days(), generated.warnings(), applied.changes(),
                applied.updatedBy(), applied.updatedAt());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String truncate(String value) {
        String text = value.strip();
        return text.length() > REASON_MAX ? text.substring(0, REASON_MAX) : text;
    }
}
