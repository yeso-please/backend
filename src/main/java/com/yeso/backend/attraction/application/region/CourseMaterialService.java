package com.yeso.backend.attraction.application.region;

import com.yeso.backend.attraction.domain.AttractionCategory;
import com.yeso.backend.attraction.infrastructure.AttractionQueryRepository;
import com.yeso.backend.attraction.infrastructure.AttractionQueryRepository.OfficialCourseStopRow;
import com.yeso.backend.shared.embedding.VectorCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 코스 생성·조회(trip 5장)에 쓰는 관광지 쪽 재료. 다른 모듈이 쓰는 공개 계약이다.
 * 후보 관광지는 {@link RegionEligibilityService#findCourseCandidates}를 쓴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseMaterialService {

    private final AttractionQueryRepository attractionQueryRepository;
    private final CourseDataCache courseDataCache;

    /** 코스 화면에 그릴 관광지 정보. 코스를 볼 때마다 현재 데이터로 읽는다(2026-09-27). */
    public record AttractionView(
            Long attractionId, String name, AttractionCategory category, int stayMinutes, String address,
            Double lat, Double lng, String thumbnailUrl, boolean recommendable) {
    }

    /** 생성형 문장 근거로 보내는 관광지 공개 데이터(이름·TourAPI 분류코드·원천 설명). */
    public record PlaceText(String name, String lclsSystm1, String lclsSystm2, String lclsSystm3, String description) {
    }

    public Map<Long, PlaceText> findPlaceTexts(Collection<Long> attractionIds) {
        Map<Long, PlaceText> texts = new HashMap<>();
        attractionQueryRepository.findPlaceTexts(attractionIds).forEach(row -> texts.put(row.id(), new PlaceText(
                row.name(), row.lclsSystm1(), row.lclsSystm2(), row.lclsSystm3(), row.description())));
        return texts;
    }

    /** 승인된 한 줄 소개(#89). {@code basis}는 {@code SOURCE_SUMMARY} 또는 {@code NAME_CATEGORY}. */
    public record ApprovedSummary(String oneLine, String basis) {
    }

    /** 승인된(APPROVED) 한 줄 소개만. 없는 관광지는 맵에 없다. */
    public Map<Long, ApprovedSummary> findApprovedSummaries(Collection<Long> attractionIds) {
        Map<Long, ApprovedSummary> summaries = new HashMap<>();
        attractionQueryRepository.findApprovedAttractionSummaries(attractionIds).forEach(row ->
                summaries.put(row.attractionId(), new ApprovedSummary(row.oneLine(), row.basis())));
        return summaries;
    }

    /** 공식 코스. {@code attractionIds}는 코스에 나오는 순서다. */
    public record OfficialCourseView(String title, List<Long> attractionIds) {
    }

    public Map<Long, AttractionView> findAttractionViews(Collection<Long> attractionIds) {
        Map<Long, AttractionView> views = new HashMap<>();
        attractionQueryRepository.findAttractionViews(attractionIds).forEach(row -> views.put(row.id(), new AttractionView(
                row.id(), row.name(), AttractionCategory.fromContentType(row.contentTypeId()),
                AttractionCategory.stayMinutesOf(row.contentTypeId()), row.address(), row.lat(), row.lng(),
                row.thumbnailUrl(), row.recommendable())));
        return views;
    }

    /**
     * 관광지 취향 벡터(임베딩). 아직 없거나 바이트가 차원과 맞지 않는 관광지는 빠진다. 관광지당 약 1.5KB라 지역 후보
     * 전체를 요청마다 읽지 않도록 잠깐 기억한다({@link CourseDataCache}).
     */
    public Map<Long, float[]> findAttractionVectors(Collection<Long> attractionIds) {
        return courseDataCache.vectors(attractionIds, this::loadVectors);
    }

    /** 캐시를 거치지 않고 DB에서 읽는다({@link CourseDataWarmer}). */
    public Map<Long, float[]> loadVectors(Collection<Long> attractionIds) {
        Map<Long, float[]> vectors = new HashMap<>();
        attractionQueryRepository.findEmbeddings(attractionIds).forEach(row -> VectorCodec.tryDecode(
                row.embedding(), row.dimension()).ifPresent(vector -> vectors.put(row.attractionId(), vector)));
        return vectors;
    }

    /** 지역의 TourAPI 공식 코스. 관광지로 연결된 장소만 담는다. */
    public List<OfficialCourseView> findOfficialCourses(String sigCd) {
        Map<Long, String> titles = new LinkedHashMap<>();
        Map<Long, List<Long>> stops = new LinkedHashMap<>();
        for (OfficialCourseStopRow row : attractionQueryRepository.findOfficialCourseStops(sigCd)) {
            titles.putIfAbsent(row.courseId(), row.title());
            stops.computeIfAbsent(row.courseId(), id -> new ArrayList<>()).add(row.attractionId());
        }
        return titles.entrySet().stream()
                .map(entry -> new OfficialCourseView(entry.getValue(), List.copyOf(stops.get(entry.getKey()))))
                .toList();
    }
}
