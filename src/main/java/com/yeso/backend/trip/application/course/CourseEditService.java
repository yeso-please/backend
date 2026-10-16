package com.yeso.backend.trip.application.course;

import com.yeso.backend.attraction.application.region.RegionEligibilityService;
import com.yeso.backend.attraction.application.region.RegionEligibilityService.CourseCandidate;
import com.yeso.backend.attraction.domain.Attraction;
import com.yeso.backend.attraction.domain.AttractionNotFoundException;
import com.yeso.backend.auth.domain.User;
import com.yeso.backend.shared.exception.ErrorCode;
import com.yeso.backend.trip.application.context.TripService;
import com.yeso.backend.trip.domain.CourseAttractionException;
import com.yeso.backend.trip.domain.CourseInvalidOperationException;
import com.yeso.backend.trip.domain.CourseItem;
import com.yeso.backend.trip.domain.CourseItemOrigin;
import com.yeso.backend.trip.domain.CourseItemNotFoundException;
import com.yeso.backend.trip.domain.CourseMealRestaurant;
import com.yeso.backend.trip.domain.CourseNotFoundException;
import com.yeso.backend.trip.domain.CourseRemoval;
import com.yeso.backend.trip.domain.CourseRemovalNotFoundException;
import com.yeso.backend.trip.domain.RestaurantSnapshot;
import com.yeso.backend.trip.domain.TripNotFoundException;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.domain.TripVersionConflictException;
import com.yeso.backend.trip.infrastructure.CourseItemRepository;
import com.yeso.backend.trip.infrastructure.CourseMealRestaurantRepository;
import com.yeso.backend.trip.infrastructure.CourseRemovalRepository;
import com.yeso.backend.trip.infrastructure.TripPlanRepository;
import com.yeso.backend.trip.infrastructure.TripParticipantRepository;
import com.yeso.backend.trip.presentation.course.CourseResponse;
import com.yeso.backend.trip.presentation.course.EditCourseRequest;
import com.yeso.backend.trip.presentation.course.EditCourseRequest.Operation;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 5-3 일정 편집. 여행 행 잠금과 한 트랜잭션으로 모든 operation을 순서대로 적용한다.
 * 관광지 추가·교체·삭제·되살리기는 기준점 대비 변경 기록(D11)을 남긴다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CourseEditService {

    /** 제외·교체 사유 최대 길이(D11-2). */
    static final int MAX_NOTE_LENGTH = 100;

    private final TripService tripService;
    private final TripPlanRepository tripPlanRepository;
    private final TripParticipantRepository tripParticipantRepository;
    private final CourseItemRepository courseItemRepository;
    private final CourseMealRestaurantRepository restaurantRepository;
    private final CourseRemovalRepository removalRepository;
    private final RegionEligibilityService eligibilityService;
    private final RestaurantSelectionTokenService tokenService;
    private final CourseStorage courseStorage;
    private final CourseService courseService;
    private final EntityManager entityManager;
    private final Clock clock;

    public CourseResponse edit(Long userId, Long tripId, EditCourseRequest request) {
        validateRequest(request);
        if (!tripParticipantRepository.existsByTripPlanIdAndUserId(tripId, userId)) {
            throw new TripNotFoundException(tripId);
        }
        TripPlan trip = tripPlanRepository.lockById(tripId).orElseThrow(() -> new TripNotFoundException(tripId));
        List<CourseItem> existing = courseItemRepository.findByTripPlanIdOrderByDayIndexAscOrderIndexAsc(tripId);
        if (existing.isEmpty()) {
            throw new CourseNotFoundException(tripId);
        }
        // 존재 오류는 종료·버전 충돌보다 먼저 판정한다.
        for (Operation operation : request.operations()) {
            if (operation.itemId() != null && existing.stream().noneMatch(item -> item.itemId().equals(operation.itemId()))) {
                throw new CourseItemNotFoundException(operation.itemId());
            }
            if (operation.attractionId() != null && entityManager.find(Attraction.class, operation.attractionId()) == null) {
                throw new AttractionNotFoundException(operation.attractionId());
            }
        }
        tripService.requireNotEnded(trip);
        if (!request.version().equals(trip.getVersion())) {
            throw new TripVersionConflictException();
        }

        Editor editor = new Editor(entityManager.find(User.class, userId), LocalDateTime.now(clock));
        Map<Integer, List<CourseItem>> days = new HashMap<>();
        for (int day = 0; day <= trip.getNights(); day++) {
            days.put(day, new ArrayList<>());
        }
        existing.forEach(item -> days.get(item.getDayIndex()).add(item));
        for (Operation operation : request.operations()) {
            switch (operation.op()) {
                case "ADD" -> add(trip, days, operation, editor);
                case "REPLACE" -> replace(trip, days, operation, editor);
                case "REMOVE" -> remove(trip, days, operation, editor);
                case "RESTORE" -> restore(trip, days, operation, editor);
                case "MOVE" -> move(days, operation);
                case "SET_RESTAURANT" -> setRestaurant(userId, tripId, days, operation);
                case "CLEAR_RESTAURANT" -> clearRestaurant(days, operation);
                default -> throw invalid();
            }
        }
        List<CourseItem> ordered = new ArrayList<>();
        for (int day = 0; day <= trip.getNights(); day++) {
            List<CourseItem> items = days.get(day);
            for (int position = 0; position < items.size(); position++) {
                items.get(position).moveTo(day, position);
            }
            ordered.addAll(items);
        }
        courseStorage.recalculateTravel(trip, ordered);
        trip.setCourseUpdatedBy(entityManager.getReference(User.class, userId));
        LocalDateTime now = LocalDateTime.now(clock);
        if (trip.getCourseUpdatedAt() != null && !now.isAfter(trip.getCourseUpdatedAt())) {
            // 같은 시각의 연속 편집도 dirty update가 되어 @Version이 증가해야 한다.
            now = trip.getCourseUpdatedAt().plusNanos(1_000);
        }
        trip.setCourseUpdatedAt(now);
        entityManager.flush();
        return courseService.participantView(trip, userId);
    }

    /** 이번 요청을 보낸 사람. 변경 기록에 닉네임을 스냅샷으로 남긴다. */
    private record Editor(User user, LocalDateTime at) {
    }

    private void add(TripPlan trip, Map<Integer, List<CourseItem>> days, Operation op, Editor editor) {
        CourseCandidate candidate = eligible(trip, days, op.attractionId());
        insert(trip, days, op.dayIndex(), op.position(), candidate, CourseItemOrigin.ADDED, editor);
    }

    private void insert(TripPlan trip, Map<Integer, List<CourseItem>> days, Integer dayIndex, Integer requestedPosition,
                        CourseCandidate candidate, CourseItemOrigin origin, Editor editor) {
        List<CourseItem> target = day(days, dayIndex);
        int position = requestedPosition == null ? target.size() : requestedPosition;
        requirePosition(position, target.size());
        CourseItem item = CourseItem.added(trip, dayIndex, position,
                entityManager.getReference(Attraction.class, candidate.attractionId()), candidate.stayMinutes(),
                origin, editor.user(), editor.user().getNickname(), editor.at());
        courseItemRepository.save(item);
        target.add(position, item);
    }

    private void replace(TripPlan trip, Map<Integer, List<CourseItem>> days, Operation op, Editor editor) {
        CourseItem item = item(days, op.itemId());
        requireAttraction(item);
        CourseCandidate candidate = eligible(trip, days, op.attractionId());
        item.replaceAttraction(entityManager.getReference(Attraction.class, candidate.attractionId()), candidate.stayMinutes(),
                editor.user(), editor.user().getNickname(), attractionName(item.getAttractionId()), op.note(), editor.at());
    }

    /** 지운 관광지는 제외 기록으로 남긴다(기준점 항목·추가한 항목 모두, D11·Q6). */
    private void remove(TripPlan trip, Map<Integer, List<CourseItem>> days, Operation op, Editor editor) {
        CourseItem item = item(days, op.itemId());
        requireAttraction(item);
        days.get(item.getDayIndex()).remove(item);
        removalRepository.save(new CourseRemoval(trip.getId(), item.getAttractionId(), attractionName(item.getAttractionId()),
                item.getDayIndex(), item.getOrigin() == null ? CourseItemOrigin.BASE : item.getOrigin(),
                editor.user(), editor.user().getNickname(), op.note(), editor.at()));
        courseItemRepository.delete(item);
    }

    /**
     * 제외한 관광지를 다시 넣는다(D11-4). 기준점 항목이었으면 다시 BASE, 그 밖에는 ADDED다. 넣는 조건은 ADD와 같다
     * (같은 지역의 추천 가능 관광지, 코스에 없음). {@code dayIndex}를 생략하면 지웠던 날, {@code position}을 생략하면 맨 뒤다.
     */
    private void restore(TripPlan trip, Map<Integer, List<CourseItem>> days, Operation op, Editor editor) {
        CourseRemoval removal = removalRepository.findById(op.removalId())
                .filter(found -> found.getTripPlanId().equals(trip.getId()) && !found.isRestored())
                .orElseThrow(() -> new CourseRemovalNotFoundException(op.removalId()));
        if (removal.getAttractionId() == null) {
            throw new CourseRemovalNotFoundException(op.removalId());
        }
        CourseCandidate candidate = eligible(trip, days, removal.getAttractionId());
        CourseItemOrigin origin = removal.getOrigin() == CourseItemOrigin.BASE ? CourseItemOrigin.BASE : CourseItemOrigin.ADDED;
        insert(trip, days, op.dayIndex() == null ? removal.getDayIndex() : op.dayIndex(), op.position(), candidate, origin, editor);
        removal.markRestored(editor.at());
    }

    private String attractionName(Long attractionId) {
        Attraction attraction = entityManager.find(Attraction.class, attractionId);
        return attraction == null ? "알 수 없는 장소" : attraction.getName();
    }

    private void move(Map<Integer, List<CourseItem>> days, Operation op) {
        CourseItem item = item(days, op.itemId());
        List<CourseItem> target = day(days, op.dayIndex());
        if (item.isMeal() && item.getDayIndex() != op.dayIndex()) {
            throw invalid();
        }
        days.get(item.getDayIndex()).remove(item);
        requirePosition(op.position(), target.size());
        target.add(op.position(), item);
        item.moveTo(op.dayIndex(), op.position());
    }

    private void setRestaurant(Long userId, Long tripId, Map<Integer, List<CourseItem>> days, Operation op) {
        CourseItem slot = item(days, op.itemId());
        requireMeal(slot);
        RestaurantSnapshot snapshot = tokenService.verify(op.selectionToken(), tripId, op.itemId());
        User user = entityManager.getReference(User.class, userId);
        LocalDateTime now = LocalDateTime.now(clock);
        CourseMealRestaurant selected = restaurantRepository.findById(slot.getId()).orElse(null);
        if (selected == null) {
            selected = CourseMealRestaurant.of(slot, snapshot, user, now);
            restaurantRepository.save(selected);
        } else {
            selected.replace(snapshot, user, now);
        }
    }

    private void clearRestaurant(Map<Integer, List<CourseItem>> days, Operation op) {
        CourseItem slot = item(days, op.itemId());
        requireMeal(slot);
        restaurantRepository.deleteById(slot.getId());
    }

    /** 추가·교체할 관광지 하나만 지금 DB로 확인한다(지역 후보 전체를 읽지 않는다). */
    private CourseCandidate eligible(TripPlan trip, Map<Integer, List<CourseItem>> days, Long id) {
        Attraction attraction = entityManager.find(Attraction.class, id);
        if (attraction == null) {
            throw new AttractionNotFoundException(id);
        }
        if (!attraction.getRegion().getSigCd().equals(trip.getRegion().getSigCd())) {
            throw new CourseAttractionException(ErrorCode.ATTRACTION_REGION_MISMATCH, id);
        }
        CourseCandidate candidate = eligibilityService.findCourseCandidate(trip.getRegion().getSigCd(), id)
                .orElseThrow(() -> new CourseAttractionException(ErrorCode.ATTRACTION_NOT_RECOMMENDABLE, id));
        if (days.values().stream().flatMap(List::stream)
                .anyMatch(item -> !item.isMeal() && id.equals(item.getAttractionId()))) {
            throw new CourseAttractionException(ErrorCode.ATTRACTION_ALREADY_IN_COURSE, id);
        }
        return candidate;
    }

    private static CourseItem item(Map<Integer, List<CourseItem>> days, String itemId) {
        return days.values().stream().flatMap(List::stream)
                .filter(item -> item.itemId().equals(itemId)).findFirst()
                .orElseThrow(() -> new CourseItemNotFoundException(itemId));
    }

    private static List<CourseItem> day(Map<Integer, List<CourseItem>> days, Integer index) {
        if (index == null || !days.containsKey(index)) {
            throw invalid();
        }
        return days.get(index);
    }

    private static void requirePosition(Integer position, int max) {
        if (position == null || position < 0 || position > max) {
            throw invalid();
        }
    }

    private static void requireAttraction(CourseItem item) {
        if (item.isMeal()) {
            throw invalid();
        }
    }

    private static void requireMeal(CourseItem item) {
        if (!item.isMeal()) {
            throw invalid();
        }
    }

    private static void validateRequest(EditCourseRequest request) {
        if (request == null || request.version() == null || request.operations() == null
                || request.operations().isEmpty() || request.operations().size() > 20) {
            throw invalid();
        }
        for (Operation op : request.operations()) {
            if (op == null || op.op() == null) {
                throw invalid();
            }
            boolean noteAllowed = op.op().equals("REMOVE") || op.op().equals("REPLACE");
            if (op.note() != null && (!noteAllowed || op.note().isBlank() || op.note().length() > MAX_NOTE_LENGTH)) {
                throw invalid();
            }
            if (op.removalId() != null && !op.op().equals("RESTORE")) {
                throw invalid();
            }
            boolean valid = switch (op.op()) {
                case "ADD" -> op.itemId() == null && op.dayIndex() != null && op.attractionId() != null
                        && op.selectionToken() == null;
                case "REPLACE" -> op.itemId() != null && op.attractionId() != null && op.dayIndex() == null
                        && op.position() == null && op.selectionToken() == null;
                case "REMOVE", "CLEAR_RESTAURANT" -> op.itemId() != null && op.dayIndex() == null
                        && op.position() == null && op.attractionId() == null && op.selectionToken() == null;
                case "MOVE" -> op.itemId() != null && op.dayIndex() != null && op.position() != null
                        && op.attractionId() == null && op.selectionToken() == null;
                case "SET_RESTAURANT" -> op.itemId() != null && op.selectionToken() != null
                        && op.dayIndex() == null && op.position() == null && op.attractionId() == null;
                case "RESTORE" -> op.removalId() != null && op.itemId() == null && op.attractionId() == null
                        && op.selectionToken() == null && (op.position() == null || op.dayIndex() != null);
                default -> false;
            };
            if (!valid || op.itemId() != null && !op.itemId().matches("[am]-[1-9][0-9]*")) {
                throw invalid();
            }
        }
    }

    private static CourseInvalidOperationException invalid() {
        return new CourseInvalidOperationException("일정 편집 operation이 올바르지 않습니다.");
    }
}
