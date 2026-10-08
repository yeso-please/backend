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
import com.yeso.backend.trip.domain.CourseItemNotFoundException;
import com.yeso.backend.trip.domain.CourseItemSource;
import com.yeso.backend.trip.domain.CourseMealRestaurant;
import com.yeso.backend.trip.domain.CourseNotFoundException;
import com.yeso.backend.trip.domain.RestaurantSnapshot;
import com.yeso.backend.trip.domain.TripNotFoundException;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.domain.TripVersionConflictException;
import com.yeso.backend.trip.infrastructure.CourseItemRepository;
import com.yeso.backend.trip.infrastructure.CourseMealRestaurantRepository;
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

/** 5-3 일정 편집. 여행 행 잠금과 한 트랜잭션으로 모든 operation을 순서대로 적용한다. */
@Service
@RequiredArgsConstructor
@Transactional
public class CourseEditService {
    private final TripService tripService;
    private final TripPlanRepository tripPlanRepository;
    private final TripParticipantRepository tripParticipantRepository;
    private final CourseItemRepository courseItemRepository;
    private final CourseMealRestaurantRepository restaurantRepository;
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

        Map<Integer, List<CourseItem>> days = new HashMap<>();
        for (int day = 0; day <= trip.getNights(); day++) {
            days.put(day, new ArrayList<>());
        }
        existing.forEach(item -> days.get(item.getDayIndex()).add(item));
        Map<Long, CourseCandidate> candidates = new HashMap<>();
        eligibilityService.findCourseCandidates(trip.getRegion().getSigCd())
                .forEach(candidate -> candidates.put(candidate.attractionId(), candidate));
        for (Operation operation : request.operations()) {
            switch (operation.op()) {
                case "ADD" -> add(trip, days, operation, candidates);
                case "REPLACE" -> replace(trip, days, operation, candidates);
                case "REMOVE" -> remove(days, operation);
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
        return courseService.view(trip, CourseResponse.PARTICIPANT, List.of());
    }

    private void add(TripPlan trip, Map<Integer, List<CourseItem>> days, Operation op,
                     Map<Long, CourseCandidate> candidates) {
        CourseCandidate candidate = eligible(trip, days, op.attractionId(), candidates);
        List<CourseItem> target = day(days, op.dayIndex());
        int position = op.position() == null ? target.size() : op.position();
        requirePosition(position, target.size());
        CourseItem item = CourseItem.attraction(trip, op.dayIndex(), position,
                entityManager.getReference(Attraction.class, candidate.attractionId()), candidate.stayMinutes(),
                null, CourseItemSource.MANUAL, null);
        courseItemRepository.save(item);
        target.add(position, item);
    }

    private void replace(TripPlan trip, Map<Integer, List<CourseItem>> days, Operation op,
                         Map<Long, CourseCandidate> candidates) {
        CourseItem item = item(days, op.itemId());
        requireAttraction(item);
        CourseCandidate candidate = eligible(trip, days, op.attractionId(), candidates);
        item.replaceAttraction(entityManager.getReference(Attraction.class, candidate.attractionId()), candidate.stayMinutes());
    }

    private void remove(Map<Integer, List<CourseItem>> days, Operation op) {
        CourseItem item = item(days, op.itemId());
        requireAttraction(item);
        days.get(item.getDayIndex()).remove(item);
        courseItemRepository.delete(item);
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

    private CourseCandidate eligible(TripPlan trip, Map<Integer, List<CourseItem>> days, Long id,
                                     Map<Long, CourseCandidate> candidates) {
        Attraction attraction = entityManager.find(Attraction.class, id);
        if (attraction == null) {
            throw new AttractionNotFoundException(id);
        }
        if (!attraction.getRegion().getSigCd().equals(trip.getRegion().getSigCd())) {
            throw new CourseAttractionException(ErrorCode.ATTRACTION_REGION_MISMATCH, id);
        }
        CourseCandidate candidate = candidates.get(id);
        if (candidate == null) {
            throw new CourseAttractionException(ErrorCode.ATTRACTION_NOT_RECOMMENDABLE, id);
        }
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
