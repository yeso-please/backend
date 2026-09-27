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

    /** 코스 화면에 그릴 관광지 정보. 코스를 볼 때마다 현재 데이터로 읽는다(2026-09-27). */
    public record AttractionView(
            Long attractionId, String name, AttractionCategory category, int stayMinutes, String address,
            Double lat, Double lng, String thumbnailUrl, boolean recommendable) {
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

    /** 관광지 취향 벡터(임베딩). 아직 없거나 바이트가 차원과 맞지 않는 관광지는 빠진다. */
    public Map<Long, float[]> findAttractionVectors(Collection<Long> attractionIds) {
        Map<Long, float[]> vectors = new HashMap<>();
        attractionQueryRepository.findEmbeddings(attractionIds).forEach(row ->
                VectorCodec.tryDecode(row.embedding(), row.dimension()).ifPresent(vector -> vectors.put(row.attractionId(), vector)));
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
