package com.yeso.backend.trip.application.course;

import com.yeso.backend.attraction.application.region.RegionEligibilityService;
import com.yeso.backend.attraction.application.region.RegionEligibilityService.CourseCandidate;
import com.yeso.backend.attraction.domain.AttractionCategory;
import com.yeso.backend.trip.application.course.CourseGeneration.AttractionItem;
import com.yeso.backend.trip.application.course.CourseGeneration.Day;
import com.yeso.backend.trip.application.course.CourseGeneration.Item;
import com.yeso.backend.trip.application.course.CourseGeneration.MealItem;
import com.yeso.backend.trip.application.course.CourseGeneration.OfficialCourse;
import com.yeso.backend.trip.application.course.CourseGeneration.Request;
import com.yeso.backend.trip.application.course.CourseGeneration.Result;
import com.yeso.backend.trip.application.course.CourseGeneration.Warning;
import com.yeso.backend.trip.domain.CourseInsufficientCandidatesException;
import com.yeso.backend.trip.domain.CourseInsufficientCandidatesException.DayShortage;
import com.yeso.backend.trip.domain.MealType;
import com.yeso.backend.trip.domain.RecommendationMode;
import com.yeso.backend.trip.domain.TravelTimeEstimator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/**
 * 코스 생성 계산(docs/api/trip.md 5-1, docs/design/recommendation.md §7). DB를 모르는 순수 계산이다.
 *
 * <ol>
 *   <li>모드: 사용자가 완전 랜덤을 골랐으면 RANDOM. 아니면 요청자 취향 벡터와 관광지 벡터가 충분하면 취향 반영,
 *       아니면 공식 코스, 그것도 없으면 규칙 코스.</li>
 *   <li>고르기: 일수 × 하루 목표만큼. 취향 반영은 점수 상위(필요 수의 3배) 안에서 점수 가중 무작위.
 *       공식 코스는 무작위로 고른 공식 코스의 장소를 먼저 넣고 나머지를 무작위로 채운다. 유형이 몰리지 않게 섞는다.</li>
 *   <li>나누기: 가까운 것끼리 날짜로 묶는다. 잇기: 하루 안에서 가까운 순서로.</li>
 *   <li>식사: 점심은 그날 관광지 절반 뒤, 저녁은 끝. 시각은 없다.</li>
 * </ol>
 * 같은 입력이어도 {@code random}에 따라 다른 코스가 나온다(랜덤 여행이 컨셉). 제외 조건 "물놀이"는 분류코드(#50) 수집 전이라 아직 적용하지 않는다.
 */
@Component
public class CourseGenerator {

    /** 취향 반영 시 후보군 크기 = 필요 수 × 3. */
    static final int POOL_MULTIPLIER = 3;

    /** 점수 0인 후보도 뽑힐 수 있게 더하는 기본 가중치. */
    private static final double BASE_WEIGHT = 0.05;

    /** 같은 추천 이유 문장을 한 코스에서 쓰는 최대 횟수(docs/api/trip.md 추천 이유). */
    static final int MAX_SAME_REASON = 2;

    /** 설문 근거가 없을 때 유사도 이유를 붙이는 코스 안 상위 장소 수. */
    static final int SIMILAR_TOP = 3;

    static final String SIMILAR_REASON = "내 취향과 비슷한 장소예요";

    /** 여행기 친화도(0~1)를 취향 점수에 더하는 최대 크기. 코사인 유사도 순위를 뒤집지 않는 낮은 가중치다. */
    static final double DIARY_WEIGHT = 0.05;

    /** 제목 테마(조사 포함). 예: "경주, 역사를 따라 걷는 2일". */
    private static final Map<AttractionCategory, String> TITLE_THEMES = new EnumMap<>(Map.of(
            AttractionCategory.NATURE, "자연을",
            AttractionCategory.HISTORY_CULTURE, "역사를",
            AttractionCategory.ACTIVITY, "체험을",
            AttractionCategory.WALK_REST, "산책을"));

    public Result generate(Request request, RandomGenerator random) {
        int target = RegionEligibilityService.dailyTarget(request.scheduleDensity());
        int needed = request.days() * target;

        Map<Long, Double> tasteScores = request.randomOnly() ? Map.of() : tasteScores(request, needed);
        OfficialCourse official = request.randomOnly() || !tasteScores.isEmpty() ? null : pickOfficialCourse(request, random);
        RecommendationMode mode = request.randomOnly() ? RecommendationMode.RANDOM
                : !tasteScores.isEmpty() ? RecommendationMode.PERSONALIZED
                : official != null ? RecommendationMode.TOUR_OFFICIAL
                : RecommendationMode.RULE_BASED;

        List<CourseCandidate> selected = select(request.candidates(), needed, mode, tasteScores, official, random);
        if (selected.size() < request.days()) {
            List<DayShortage> shortages = new ArrayList<>();
            for (int day = selected.size(); day < request.days(); day++) {
                shortages.add(new DayShortage(day, 1, 0));
            }
            throw new CourseInsufficientCandidatesException(shortages);
        }

        List<List<CourseCandidate>> dayGroups = splitByDistance(selected, request.days());
        Set<Long> officialIds = official == null ? Set.of() : Set.copyOf(official.attractionIds());

        List<Day> days = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>();
        warnings.add(new Warning("ROUTE_TIME_ESTIMATED", null));
        if (mode == RecommendationMode.TOUR_OFFICIAL || mode == RecommendationMode.RULE_BASED) {
            warnings.add(new Warning("PERSONALIZATION_FALLBACK", null));
        }
        Reasons reasons = new Reasons(mode, official, officialIds, request.evidence(), topBySimilarity(selected, tasteScores));
        for (int dayIndex = 0; dayIndex < dayGroups.size(); dayIndex++) {
            List<CourseCandidate> ordered = orderByNearest(dayGroups.get(dayIndex));
            days.add(new Day(dayIndex, buildItems(ordered, request, reasons)));
            if (ordered.size() < target) {
                warnings.add(new Warning("DENSITY_TARGET_NOT_MET", dayIndex));
            }
        }
        return new Result(mode, title(request.regionName(), request.days(), selected), days, warnings);
    }

    // ---------- 모드 ----------

    /**
     * 요청자 벡터가 있고 벡터가 있는 관광지가 필요 수 이상이면 취향 점수, 아니면 빈 맵.
     * 취향 점수 = 코사인 유사도 + {@link #DIARY_WEIGHT} × 여행기 유형 친화도(없으면 0).
     */
    private static Map<Long, Double> tasteScores(Request request, int needed) {
        float[] requester = request.requesterVector();
        if (requester == null || request.attractionVectors() == null) {
            return Map.of();
        }
        Map<Long, Double> scores = new HashMap<>();
        for (CourseCandidate candidate : request.candidates()) {
            float[] vector = request.attractionVectors().get(candidate.attractionId());
            if (vector != null && vector.length == requester.length) {
                scores.put(candidate.attractionId(), cosine(requester, vector)
                        + DIARY_WEIGHT * request.evidence().affinity(candidate.category()));
            }
        }
        return scores.size() >= needed ? scores : Map.of();
    }

    /** 추천 가능 관광지로 연결된 장소가 하나라도 있는 공식 코스 중 하나를 무작위로. 없으면 null. */
    private static OfficialCourse pickOfficialCourse(Request request, RandomGenerator random) {
        Set<Long> candidateIds = request.candidates().stream()
                .map(CourseCandidate::attractionId).collect(Collectors.toSet());
        List<OfficialCourse> usable = request.officialCourses() == null ? List.of() : request.officialCourses().stream()
                .map(course -> new OfficialCourse(course.title(),
                        course.attractionIds().stream().filter(candidateIds::contains).distinct().toList()))
                .filter(course -> !course.attractionIds().isEmpty())
                .toList();
        return usable.isEmpty() ? null : usable.get(random.nextInt(usable.size()));
    }

    // ---------- 고르기 ----------

    private static List<CourseCandidate> select(
            List<CourseCandidate> candidates, int needed, RecommendationMode mode, Map<Long, Double> tasteScores,
            OfficialCourse official, RandomGenerator random) {
        Map<Long, CourseCandidate> byId = candidates.stream()
                .collect(Collectors.toMap(CourseCandidate::attractionId, c -> c, (a, b) -> a));
        List<CourseCandidate> picked = new ArrayList<>();

        if (mode == RecommendationMode.TOUR_OFFICIAL) {
            for (Long id : official.attractionIds()) {
                if (picked.size() < needed) {
                    picked.add(byId.get(id));
                }
            }
        }

        List<CourseCandidate> pool = new ArrayList<>(candidates);
        pool.removeAll(picked);
        Map<CourseCandidate, Double> weights = new HashMap<>();
        if (mode == RecommendationMode.PERSONALIZED) {
            pool.sort(Comparator.comparingDouble((CourseCandidate c) -> tasteScores.getOrDefault(c.attractionId(), 0.0))
                    .reversed());
            pool = new ArrayList<>(pool.subList(0, Math.min(pool.size(), needed * POOL_MULTIPLIER)));
            for (CourseCandidate candidate : pool) {
                weights.put(candidate, Math.max(0, tasteScores.getOrDefault(candidate.attractionId(), 0.0)) + BASE_WEIGHT);
            }
        } else {
            pool.forEach(candidate -> weights.put(candidate, 1.0));
        }

        // 유형이 몰리지 않게: 이미 뽑힌 같은 유형 수만큼 가중치를 낮춘다.
        Map<AttractionCategory, Integer> categoryCounts = new EnumMap<>(AttractionCategory.class);
        picked.forEach(c -> categoryCounts.merge(c.category(), 1, Integer::sum));
        while (picked.size() < needed && !pool.isEmpty()) {
            double total = 0;
            double[] effective = new double[pool.size()];
            for (int i = 0; i < pool.size(); i++) {
                CourseCandidate candidate = pool.get(i);
                effective[i] = weights.get(candidate) / (1 + categoryCounts.getOrDefault(candidate.category(), 0));
                total += effective[i];
            }
            double roll = random.nextDouble() * total;
            int chosen = pool.size() - 1;
            for (int i = 0; i < pool.size(); i++) {
                roll -= effective[i];
                if (roll < 0) {
                    chosen = i;
                    break;
                }
            }
            CourseCandidate candidate = pool.remove(chosen);
            picked.add(candidate);
            categoryCounts.merge(candidate.category(), 1, Integer::sum);
        }
        return picked;
    }

    // ---------- 나누기·잇기 ----------

    /**
     * 가까운 것끼리 날짜로 묶는다. 서로 먼 장소를 날짜별 기준점으로 잡고(k-center), 가까운 기준점부터
     * 날짜 정원(균등 분배)이 찰 때까지 배정한다.
     */
    static List<List<CourseCandidate>> splitByDistance(List<CourseCandidate> selected, int days) {
        int n = selected.size();
        int[] capacity = new int[days];
        for (int day = 0; day < days; day++) {
            capacity[day] = n / days + (day < n % days ? 1 : 0);
        }

        double[] centroid = centroid(selected);
        List<CourseCandidate> seeds = new ArrayList<>();
        seeds.add(farthestFrom(selected, List.of(centroid)));
        while (seeds.size() < days) {
            List<double[]> seedPoints = seeds.stream().map(s -> new double[]{s.lat(), s.lng()}).toList();
            seeds.add(farthestFrom(selected, seedPoints));
        }

        record Pair(CourseCandidate point, int day, double distance) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (CourseCandidate point : selected) {
            for (int day = 0; day < days; day++) {
                CourseCandidate seed = seeds.get(day);
                pairs.add(new Pair(point, day,
                        TravelTimeEstimator.distanceKm(point.lat(), point.lng(), seed.lat(), seed.lng())));
            }
        }
        pairs.sort(Comparator.comparingDouble(Pair::distance));

        List<List<CourseCandidate>> groups = new ArrayList<>();
        for (int day = 0; day < days; day++) {
            groups.add(new ArrayList<>());
        }
        Set<Long> assigned = new LinkedHashSet<>();
        for (Pair pair : pairs) {
            if (!assigned.contains(pair.point().attractionId()) && groups.get(pair.day()).size() < capacity[pair.day()]) {
                groups.get(pair.day()).add(pair.point());
                assigned.add(pair.point().attractionId());
            }
        }
        return groups;
    }

    /** 무리의 가장자리(중심에서 가장 먼 곳)에서 시작해 가장 가까운 곳으로 이어간다. */
    static List<CourseCandidate> orderByNearest(List<CourseCandidate> group) {
        if (group.size() <= 1) {
            return List.copyOf(group);
        }
        List<CourseCandidate> remaining = new ArrayList<>(group);
        CourseCandidate current = farthestFrom(remaining, List.of(centroid(remaining)));
        List<CourseCandidate> ordered = new ArrayList<>();
        while (true) {
            ordered.add(current);
            remaining.remove(current);
            if (remaining.isEmpty()) {
                return ordered;
            }
            CourseCandidate from = current;
            current = remaining.stream()
                    .min(Comparator.comparingDouble(c -> TravelTimeEstimator.distanceKm(from.lat(), from.lng(), c.lat(), c.lng())))
                    .orElseThrow();
        }
    }

    private static double[] centroid(List<CourseCandidate> points) {
        double lat = points.stream().mapToDouble(CourseCandidate::lat).average().orElse(0);
        double lng = points.stream().mapToDouble(CourseCandidate::lng).average().orElse(0);
        return new double[]{lat, lng};
    }

    /** {@code anchors} 중 가장 가까운 것까지의 거리가 가장 먼 점. 동률이면 관광지 ID가 작은 것. */
    private static CourseCandidate farthestFrom(List<CourseCandidate> points, List<double[]> anchors) {
        CourseCandidate best = null;
        double bestDistance = -1;
        for (CourseCandidate point : points) {
            double nearest = anchors.stream()
                    .mapToDouble(a -> TravelTimeEstimator.distanceKm(point.lat(), point.lng(), a[0], a[1]))
                    .min().orElse(0);
            if (nearest > bestDistance || (nearest == bestDistance && point.attractionId() < best.attractionId())) {
                best = point;
                bestDistance = nearest;
            }
        }
        return best;
    }

    // ---------- 항목·식사·이유·제목 ----------

    /** 점심은 그날 관광지 절반 뒤, 저녁은 끝. 이동시간은 앞 관광지에서 잰다(식사는 건너뛴다). */
    private static List<Item> buildItems(List<CourseCandidate> ordered, Request request, Reasons reasons) {
        int lunchAfter = (ordered.size() + 1) / 2;
        List<Item> items = new ArrayList<>();
        CourseCandidate previous = null;
        for (int i = 0; i < ordered.size(); i++) {
            CourseCandidate current = ordered.get(i);
            Integer travel = previous == null ? null : TravelTimeEstimator.minutes(
                    previous.lat(), previous.lng(), current.lat(), current.lng(), request.transport());
            items.add(new AttractionItem(current, travel, reasons.next(current)));
            previous = current;
            if (i + 1 == lunchAfter) {
                items.add(new MealItem(MealType.LUNCH));
            }
        }
        items.add(new MealItem(MealType.DINNER));
        return items;
    }

    /** 취향 반영 모드에서 고른 장소 중 취향 유사도 상위 {@link #SIMILAR_TOP}곳. 점수가 없으면 빈 집합. */
    private static Set<Long> topBySimilarity(List<CourseCandidate> selected, Map<Long, Double> tasteScores) {
        if (tasteScores.isEmpty()) {
            return Set.of();
        }
        return selected.stream()
                .filter(c -> tasteScores.containsKey(c.attractionId()))
                .sorted(Comparator.comparingDouble((CourseCandidate c) -> tasteScores.get(c.attractionId())).reversed()
                        .thenComparing(CourseCandidate::attractionId))
                .limit(SIMILAR_TOP)
                .map(CourseCandidate::attractionId)
                .collect(Collectors.toSet());
    }

    /**
     * 추천 이유를 코스 순서대로 정한다(docs/api/trip.md 추천 이유). 근거가 없으면 null.
     * 같은 문장은 코스 안에서 {@link #MAX_SAME_REASON}번까지만 쓰므로 순서대로 호출한다.
     */
    private static final class Reasons {
        private final RecommendationMode mode;
        private final OfficialCourse official;
        private final Set<Long> officialIds;
        private final TasteEvidence evidence;
        private final Set<Long> similarTop;
        private final Map<String, Integer> used = new HashMap<>();

        Reasons(RecommendationMode mode, OfficialCourse official, Set<Long> officialIds, TasteEvidence evidence,
                Set<Long> similarTop) {
            this.mode = mode;
            this.official = official;
            this.officialIds = officialIds;
            this.evidence = evidence;
            this.similarTop = similarTop;
        }

        String next(CourseCandidate candidate) {
            if (mode == RecommendationMode.TOUR_OFFICIAL && officialIds.contains(candidate.attractionId())) {
                return "관광공사 추천 코스 「" + official.title() + "」에 나오는 곳이에요";
            }
            if (mode != RecommendationMode.PERSONALIZED) {
                return null;
            }
            for (String reason : evidence.reasonsFor(candidate.category())) {
                if (used.getOrDefault(reason, 0) < MAX_SAME_REASON) {
                    used.merge(reason, 1, Integer::sum);
                    return reason;
                }
            }
            return similarTop.contains(candidate.attractionId()) ? SIMILAR_REASON : null;
        }
    }

    /** "{지역}, {대표 테마}를 따라 걷는 {일수}일". 대표 유형이 없으면 "{지역}에서 보내는 {일수}일". */
    static String title(String regionName, int days, List<CourseCandidate> selected) {
        String region = shortRegionName(regionName);
        return selected.stream()
                .map(CourseCandidate::category)
                .filter(TITLE_THEMES::containsKey)
                .collect(Collectors.groupingBy(category -> category, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.<AttractionCategory, Long>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(top -> region + ", " + TITLE_THEMES.get(top.getKey()) + " 따라 걷는 " + days + "일")
                .orElse(region + "에서 보내는 " + days + "일");
    }

    /** "경주시" → "경주". 두 글자 이름("중구")은 그대로 둔다. */
    static String shortRegionName(String regionName) {
        if (regionName != null && regionName.length() > 2
                && (regionName.endsWith("시") || regionName.endsWith("군") || regionName.endsWith("구"))) {
            return regionName.substring(0, regionName.length() - 1);
        }
        return regionName;
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return normA == 0 || normB == 0 ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
