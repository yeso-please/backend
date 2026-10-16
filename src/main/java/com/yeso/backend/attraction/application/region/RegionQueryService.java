package com.yeso.backend.attraction.application.region;

import com.yeso.backend.attraction.application.region.RegionEligibilityService.CourseCandidate;
import com.yeso.backend.attraction.application.region.RegionEligibilityService.RegionEligibility;
import com.yeso.backend.attraction.domain.AttractionCategory;
import com.yeso.backend.attraction.domain.AttractionInvalidBoundsException;
import com.yeso.backend.attraction.domain.AttractionNotFoundException;
import com.yeso.backend.attraction.domain.InvalidAttractionQueryException;
import com.yeso.backend.attraction.domain.Region;
import com.yeso.backend.attraction.domain.RegionContentNotReadyException;
import com.yeso.backend.attraction.domain.RegionNotFoundException;
import com.yeso.backend.attraction.domain.SummaryTags;
import com.yeso.backend.attraction.infrastructure.AttractionQueryRepository;
import com.yeso.backend.attraction.infrastructure.AttractionQueryRepository.Bounds;
import com.yeso.backend.attraction.infrastructure.AttractionQueryRepository.DetailRow;
import com.yeso.backend.attraction.infrastructure.AttractionQueryRepository.PinRow;
import com.yeso.backend.attraction.infrastructure.AttractionQueryRepository.AttractionSummaryRow;
import com.yeso.backend.attraction.infrastructure.AttractionQueryRepository.RegionContentRow;
import com.yeso.backend.attraction.infrastructure.AttractionQueryRepository.RegionSummaryRow;
import com.yeso.backend.attraction.infrastructure.AttractionQueryRepository.RepresentativeRow;
import com.yeso.backend.attraction.infrastructure.RegionRepository;
import com.yeso.backend.attraction.presentation.region.AttractionDetailResponse;
import com.yeso.backend.attraction.presentation.region.AttractionDetailResponse.NotRecommendableReason;
import com.yeso.backend.attraction.presentation.region.AttractionDetailResponse.SummaryBasis;
import com.yeso.backend.attraction.presentation.region.AttractionPinsResponse;
import com.yeso.backend.attraction.presentation.region.RegionCardResponse;
import com.yeso.backend.attraction.presentation.region.RegionListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 지역·관광지 조회(docs/api/attraction.md 7-1~7-4). 회원만 부른다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegionQueryService {

    private static final String DEFAULT_DENSITY = "RELAXED";
    private static final int DEFAULT_PIN_LIMIT = 100;
    private static final int MAX_PIN_LIMIT = 200;
    private static final int MAX_LANDMARKS = 3;
    private static final String CURSOR_PREFIX = "p:";
    private static final String TOUR_API = "TOUR_API";
    private static final String TOUR_API_SOURCE_NAME = "한국관광공사";
    private static final String TOUR_API_DATASET_NAME = "한국관광공사 TourAPI";

    // 한국 범위(docs/api/attraction.md 7-3)
    private static final double MIN_LAT = 33;
    private static final double MAX_LAT = 39;
    private static final double MIN_LNG = 124;
    private static final double MAX_LNG = 132;

    private final RegionRepository regionRepository;
    private final RegionEligibilityService regionEligibilityService;
    private final AttractionQueryRepository attractionQueryRepository;
    private final JsonMapper jsonMapper;

    // ---------- 7-1 ----------

    /** {@code days}가 없으면 추첨 여부를 계산하지 않는다. 밀도를 생략하면 {@code RELAXED}다. */
    public RegionListResponse listRegions(Integer days, String scheduleDensity) {
        if (days == null) {
            List<RegionListResponse.RegionItem> regions = regionRepository.findAll(Sort.by("sigCd")).stream()
                    .map(region -> new RegionListResponse.RegionItem(
                            region.getSigCd(), region.getProvince(), region.getCity(), region.getLat(), region.getLng(),
                            null, null))
                    .toList();
            return new RegionListResponse(null, null, null, regions);
        }
        String density = scheduleDensity == null ? DEFAULT_DENSITY : requireDensity(scheduleDensity);
        List<RegionEligibility> evaluated = regionEligibilityService.evaluateAll(days, density);
        long eligibleCount = evaluated.stream().filter(RegionEligibility::drawEligible).count();
        List<RegionListResponse.RegionItem> regions = evaluated.stream()
                .map(region -> new RegionListResponse.RegionItem(
                        region.sigCd(), region.province(), region.city(), region.centerLat(), region.centerLng(),
                        region.drawEligible(), region.ineligibleReasons()))
                .toList();
        return new RegionListResponse(days, density, eligibleCount, regions);
    }

    // ---------- 7-2 ----------

    private record LandmarkRef(Long attractionId, Integer order) {
    }

    private record SourceRef(String title, String url) {
    }

    /** 지역 소개가 준비되지 않은 경우에도 추첨 결과를 확인할 수 있도록 빈 콘텐츠 카드로 응답한다. */
    public RegionCardResponse regionCard(String sigCd) {
        Region region = requireRegion(sigCd);
        RegionContentRow content = attractionQueryRepository.findLatestApprovedContent(sigCd).orElse(null);

        Map<Long, CourseCandidate> recommendable = regionEligibilityService.findCourseCandidates(sigCd).stream()
                .collect(Collectors.toMap(CourseCandidate::attractionId, Function.identity()));
        // 대표 관광지는 소개문에 나온 순서대로, 지금 추천 가능한 것만 최대 3곳.
        List<RegionCardResponse.Landmark> landmarks = content == null ? List.of() : readList(content.landmarksJson(), new TypeReference<List<LandmarkRef>>() { })
                .stream()
                .sorted(Comparator.comparing(LandmarkRef::order, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(ref -> recommendable.get(ref.attractionId()))
                .filter(candidate -> candidate != null)
                .limit(MAX_LANDMARKS)
                .map(candidate -> new RegionCardResponse.Landmark(
                        candidate.attractionId(), candidate.name(), candidate.thumbnailUrl()))
                .toList();

        boolean hasValidHeroImage = content != null && "VALID".equals(content.heroImageStatus());
        RegionSummaryRow summary = attractionQueryRepository.findApprovedRegionSummary(sigCd).orElse(null);
        return new RegionCardResponse(
                region.getSigCd(), region.getProvince(), region.getCity(), content == null ? region.getCity() : content.title(),
                summary == null ? null : summary.tagline(),
                summary == null ? List.of() : tags(summary.tagsJson()),
                content == null ? List.of() : paragraphs(content.introduction()),
                hasValidHeroImage ? new RegionCardResponse.HeroImage(
                        content.heroImageUrl(), content.heroImageSourceName(), content.heroImageSourceUrl(),
                        content.heroImageLicense(), null) : representativeHeroImage(sigCd),
                content == null ? List.of() : readList(content.characteristicsJson(), new TypeReference<List<String>>() { }),
                content == null ? List.of() : commaSeparated(content.historyTags()),
                landmarks,
                content == null ? List.of() : readList(content.sourcesJson(), new TypeReference<List<SourceRef>>() { }).stream()
                        .map(source -> new RegionCardResponse.Source(source.title(), source.url()))
                        .toList(),
                content == null ? null : content.updatedAt());
    }

    // ---------- 7-3 ----------

    public AttractionPinsResponse pins(
            String sigCd, String bbox, AttractionCategory category, String cursor, Integer limit) {
        requireRegion(sigCd);
        int pageSize = limit == null ? DEFAULT_PIN_LIMIT : limit;
        if (pageSize < 1 || pageSize > MAX_PIN_LIMIT) {
            throw new InvalidAttractionQueryException("limit은 1~200이어야 합니다: " + pageSize);
        }
        List<PinRow> rows = attractionQueryRepository.findPins(
                sigCd, parseBounds(bbox), category == null ? null : category.name(), decodeCursor(cursor), pageSize + 1);
        boolean hasNext = rows.size() > pageSize;
        List<PinRow> page = hasNext ? rows.subList(0, pageSize) : rows;
        List<AttractionPinsResponse.Pin> items = page.stream()
                .map(row -> new AttractionPinsResponse.Pin(
                        row.id(), row.name(), row.thumbnailUrl(), AttractionCategory.fromContentType(row.contentTypeId()),
                        row.lat(), row.lng(), row.recommendable()))
                .toList();
        return new AttractionPinsResponse(items, hasNext ? encodeCursor(page.get(page.size() - 1).id()) : null);
    }

    // ---------- 7-4 ----------

    public AttractionDetailResponse attractionDetail(Long attractionId) {
        DetailRow row = attractionQueryRepository.findDetail(attractionId)
                .orElseThrow(() -> new AttractionNotFoundException(attractionId));
        boolean fromTourApi = TOUR_API.equals(row.sourceSystem());

        List<NotRecommendableReason> reasons = new ArrayList<>();
        if (row.description() == null || row.description().isBlank()) {
            reasons.add(NotRecommendableReason.MISSING_DESCRIPTION);
        }
        if (!row.hasValidImage()) {
            reasons.add(NotRecommendableReason.MISSING_IMAGE);
        }
        if (row.lat() == null || row.lng() == null) {
            reasons.add(NotRecommendableReason.MISSING_COORDINATE);
        }
        if (row.hasBlockingIssue()) {
            reasons.add(NotRecommendableReason.QUALITY_ISSUE);
        }

        List<AttractionDetailResponse.Image> images = attractionQueryRepository.findValidImages(attractionId).stream()
                .map(image -> new AttractionDetailResponse.Image(
                        image.url(), fromTourApi ? TOUR_API_SOURCE_NAME : null, image.license()))
                .toList();
        List<AttractionDetailResponse.Source> sources = row.sourceContentId() == null
                ? List.of()
                : List.of(new AttractionDetailResponse.Source(
                        fromTourApi ? TOUR_API_DATASET_NAME : row.sourceSystem(), row.sourceContentId(), row.detailFetchedAt()));

        AttractionSummaryRow summary = attractionQueryRepository.findApprovedAttractionSummary(attractionId).orElse(null);
        return new AttractionDetailResponse(
                row.id(), row.regionSigCd(), row.name(), AttractionCategory.fromContentType(row.contentTypeId()),
                row.address(), row.lat(), row.lng(), row.description(), images, row.useTime(), row.restDate(),
                AttractionCategory.stayMinutesOf(row.contentTypeId()), true, reasons.isEmpty(), List.copyOf(reasons),
                sources,
                summary == null ? null : summary.oneLine(),
                summary == null ? List.of() : tags(summary.tagsJson()),
                summary == null ? null : SummaryBasis.valueOf(summary.basis()));
    }

    // ---------- helpers ----------

    /** 승인된 지역 소개에 쓸 사진이 없으면 대표 관광지의 검증된 사진으로 채운다(원천 이미지라 승인 없이 쓴다). */
    private RegionCardResponse.HeroImage representativeHeroImage(String sigCd) {
        return attractionQueryRepository.findRepresentativeAttractions(sigCd, 1).stream()
                .findFirst()
                .map(RegionQueryService::heroImageOf)
                .orElse(null);
    }

    private static RegionCardResponse.HeroImage heroImageOf(RepresentativeRow row) {
        return new RegionCardResponse.HeroImage(row.imageUrl(), TOUR_API.equals(row.sourceSystem()) ? TOUR_API_SOURCE_NAME : null,
                null, row.imageLicense(), row.id());
    }

    private List<String> tags(String json) {
        return SummaryTags.filterKnown(readList(json, new TypeReference<List<String>>() { }));
    }

    private Region requireRegion(String sigCd) {
        return regionRepository.findById(sigCd).orElseThrow(() -> new RegionNotFoundException(sigCd));
    }

    private static String requireDensity(String scheduleDensity) {
        if (!scheduleDensity.equals("RELAXED") && !scheduleDensity.equals("PACKED")) {
            throw new InvalidAttractionQueryException("scheduleDensity는 RELAXED 또는 PACKED여야 합니다: " + scheduleDensity);
        }
        return scheduleDensity;
    }

    /** {@code minLng,minLat,maxLng,maxLat}. 형식 오류, 최소 > 최대, 한국 범위 밖이면 {@code ATTRACTION_INVALID_BOUNDS}. */
    private static Bounds parseBounds(String bbox) {
        if (bbox == null) {
            return null;
        }
        String[] parts = bbox.split(",");
        if (parts.length != 4) {
            throw new AttractionInvalidBoundsException(bbox);
        }
        double[] values;
        try {
            values = Arrays.stream(parts).mapToDouble(part -> Double.parseDouble(part.trim())).toArray();
        } catch (NumberFormatException e) {
            throw new AttractionInvalidBoundsException(bbox);
        }
        Bounds bounds = new Bounds(values[0], values[1], values[2], values[3]);
        boolean ordered = bounds.minLng() <= bounds.maxLng() && bounds.minLat() <= bounds.maxLat();
        boolean inKorea = bounds.minLng() >= MIN_LNG && bounds.maxLng() <= MAX_LNG
                && bounds.minLat() >= MIN_LAT && bounds.maxLat() <= MAX_LAT;
        if (!ordered || !inKorea) {
            throw new AttractionInvalidBoundsException(bbox);
        }
        return bounds;
    }

    private static String encodeCursor(Long lastId) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((CURSOR_PREFIX + lastId).getBytes(StandardCharsets.UTF_8));
    }

    private static Long decodeCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!decoded.startsWith(CURSOR_PREFIX)) {
                throw new InvalidAttractionQueryException("cursor가 올바르지 않습니다.");
            }
            return Long.parseLong(decoded.substring(CURSOR_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new InvalidAttractionQueryException("cursor가 올바르지 않습니다.");
        }
    }

    /** 소개문은 빈 줄로 문단을 나눈다. */
    private static List<String> paragraphs(String introduction) {
        return Arrays.stream(introduction.split("\\n\\s*\\n"))
                .map(String::strip)
                .filter(paragraph -> !paragraph.isEmpty())
                .toList();
    }

    /** 역사 태그는 쉼표로 구분한 문자열이다. */
    private static List<String> commaSeparated(String tags) {
        if (tags == null) {
            return List.of();
        }
        return Arrays.stream(tags.split(","))
                .map(String::strip)
                .filter(tag -> !tag.isEmpty())
                .toList();
    }

    private <T> List<T> readList(String json, TypeReference<List<T>> type) {
        if (json == null) {
            return List.of();
        }
        return jsonMapper.readValue(json, type);
    }
}
