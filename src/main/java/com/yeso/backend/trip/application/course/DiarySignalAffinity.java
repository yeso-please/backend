package com.yeso.backend.trip.application.course;

import com.yeso.backend.attraction.domain.AttractionCategory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 여행기 취향 신호(발행한 여행기의 만족도·경험 태그) → 관광지 유형별 친화도 0~1(docs/design/recommendation.md 여행기 신호).
 * DB를 모르는 순수 계산이다. 본문·사진·EXIF·열람 수는 쓰지 않는다.
 *
 * <ul>
 *   <li>최근 {@link #MAX_SIGNALS}건만 쓴다. 발행 시점 기준 반감기 {@link #HALF_LIFE_DAYS}일로 약하게 한다.</li>
 *   <li>만족도 4~5 → 가중치 1, 3 또는 미입력 → 0.5, 1~2 → 쓰지 않는다(감점하지 않는다).</li>
 *   <li>유형 친화도 = 그 유형으로 이어지는 태그를 고른 신호의 가중치 합 ÷ 전체 신호 가중치 합.</li>
 * </ul>
 */
public final class DiarySignalAffinity {

    static final int MAX_SIGNALS = 10;
    static final double HALF_LIFE_DAYS = 180;

    /** 경험 태그 → 관광지 유형. 시장·로컬 음식은 관광지 유형이 아니라 쓰지 않는다. */
    static final Map<String, AttractionCategory> TAG_CATEGORIES = Map.ofEntries(
            Map.entry("자연", AttractionCategory.NATURE),
            Map.entry("바다", AttractionCategory.NATURE),
            Map.entry("산", AttractionCategory.NATURE),
            Map.entry("산책", AttractionCategory.WALK_REST),
            Map.entry("골목", AttractionCategory.WALK_REST),
            Map.entry("휴식", AttractionCategory.WALK_REST),
            Map.entry("카페", AttractionCategory.WALK_REST),
            Map.entry("역사", AttractionCategory.HISTORY_CULTURE),
            Map.entry("실내", AttractionCategory.HISTORY_CULTURE),
            Map.entry("체험", AttractionCategory.ACTIVITY));

    /** @param publishedAt 신호를 저장한 시각(발행·수정) */
    public record Signal(Short satisfaction, List<String> tags, LocalDateTime publishedAt) {
    }

    /**
     * @param affinity 유형별 친화도 0~1. 신호가 없으면 빈 맵
     * @param topTag   유형별로 가중치가 가장 큰 태그(추천 이유 문장용)
     */
    public record Result(Map<AttractionCategory, Double> affinity, Map<AttractionCategory, String> topTag) {
        public static final Result NONE = new Result(Map.of(), Map.of());
    }

    private DiarySignalAffinity() {
    }

    /** {@code signals}는 최신순. 앞의 {@link #MAX_SIGNALS}건만 쓴다. */
    public static Result compute(List<Signal> signals, LocalDateTime now) {
        Map<AttractionCategory, Double> support = new EnumMap<>(AttractionCategory.class);
        Map<AttractionCategory, Map<String, Double>> tagWeights = new EnumMap<>(AttractionCategory.class);
        double total = 0;
        for (Signal signal : signals.stream().limit(MAX_SIGNALS).toList()) {
            double weight = satisfactionWeight(signal.satisfaction()) * decay(signal.publishedAt(), now);
            if (weight <= 0) {
                continue;
            }
            total += weight;
            Map<AttractionCategory, String> firstTagByCategory = new EnumMap<>(AttractionCategory.class);
            for (String tag : signal.tags()) {
                AttractionCategory category = TAG_CATEGORIES.get(tag);
                if (category != null) {
                    firstTagByCategory.putIfAbsent(category, tag);
                    tagWeights.computeIfAbsent(category, c -> new java.util.HashMap<>()).merge(tag, weight, Double::sum);
                }
            }
            firstTagByCategory.keySet().forEach(category -> support.merge(category, weight, Double::sum));
        }
        if (total <= 0) {
            return Result.NONE;
        }
        Map<AttractionCategory, Double> affinity = new EnumMap<>(AttractionCategory.class);
        double finalTotal = total;
        support.forEach((category, weight) -> affinity.put(category, weight / finalTotal));
        Map<AttractionCategory, String> topTag = new EnumMap<>(AttractionCategory.class);
        tagWeights.forEach((category, weights) -> weights.entrySet().stream()
                .max(Map.Entry.<String, Double>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .ifPresent(best -> topTag.put(category, best.getKey())));
        return new Result(Map.copyOf(affinity), Map.copyOf(topTag));
    }

    static double satisfactionWeight(Short satisfaction) {
        if (satisfaction == null || satisfaction == 3) {
            return 0.5;
        }
        return satisfaction >= 4 ? 1.0 : 0.0;
    }

    static double decay(LocalDateTime publishedAt, LocalDateTime now) {
        if (publishedAt == null || !publishedAt.isBefore(now)) {
            return 1.0;
        }
        double days = Duration.between(publishedAt, now).toHours() / 24.0;
        return Math.pow(0.5, days / HALF_LIFE_DAYS);
    }
}
