package com.yeso.backend.trip.application.course;

import com.yeso.backend.attraction.domain.AttractionCategory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 추천 근거(docs/api/trip.md 추천 이유). 요청자의 실제 설문 답 중 관광지 유형과 이어지는 것을 문장으로 두고,
 * 여행기 취향 신호가 있으면 유형별 친화도({@link #affinity})와 여행기 근거 문장을 더한다.
 * DB를 모르는 값 객체다. 근거가 없으면 {@link #NONE}.
 */
public final class TasteEvidence {

    public static final TasteEvidence NONE = new TasteEvidence(Map.of(), Map.of());

    /** 여행기 근거 문장을 붙이는 최소 친화도(신호 가중치의 절반 이상이 그 유형을 가리킬 때). */
    static final double DIARY_REASON_MIN_AFFINITY = 0.5;

    static final String NATURE_STYLE = "자연을 좋아하는 취향과 맞아요";
    static final String REST_STYLE = "휴식을 좋아하는 취향과 맞아요";
    static final String ACTIVITY_STYLE = "체험 활동을 좋아하는 취향과 맞아요";

    /** 여행 동기 코드 → (유형, 문장). 나머지 동기는 관광지 유형과 직접 이어지지 않아 쓰지 않는다. */
    private static final Map<Integer, Map.Entry<AttractionCategory, String>> MOTIVES = Map.of(
            2, Map.entry(AttractionCategory.WALK_REST, "'휴식과 재충전' 여행 동기와 맞아요"),
            6, Map.entry(AttractionCategory.ACTIVITY, "'운동과 건강' 여행 동기와 맞아요"),
            7, Map.entry(AttractionCategory.ACTIVITY, "'새로운 경험' 여행 동기와 맞아요"),
            8, Map.entry(AttractionCategory.HISTORY_CULTURE, "'역사와 문화 탐방' 여행 동기와 맞아요"));

    private final Map<AttractionCategory, List<String>> reasons;
    private final Map<AttractionCategory, Double> affinity;

    private TasteEvidence(Map<AttractionCategory, List<String>> reasons, Map<AttractionCategory, Double> affinity) {
        this.reasons = reasons;
        this.affinity = affinity;
    }

    /**
     * @param travelStyles  여행 스타일 문항 번호 → 1~7 (4는 중립이라 근거가 아니다)
     * @param travelMotives 여행 동기 코드
     */
    public static TasteEvidence from(Map<Integer, Integer> travelStyles, List<Integer> travelMotives) {
        Map<AttractionCategory, List<String>> reasons = new EnumMap<>(AttractionCategory.class);
        Integer nature = travelStyles == null ? null : travelStyles.get(1);
        if (nature != null && nature <= 3) {
            add(reasons, AttractionCategory.NATURE, NATURE_STYLE);
            add(reasons, AttractionCategory.WALK_REST, NATURE_STYLE);
        }
        Integer restActivity = travelStyles == null ? null : travelStyles.get(5);
        if (restActivity != null && restActivity <= 3) {
            add(reasons, AttractionCategory.WALK_REST, REST_STYLE);
        } else if (restActivity != null && restActivity >= 5) {
            add(reasons, AttractionCategory.ACTIVITY, ACTIVITY_STYLE);
        }
        if (travelMotives != null) {
            for (Integer motive : travelMotives) {
                var entry = MOTIVES.get(motive);
                if (entry != null) {
                    add(reasons, entry.getKey(), entry.getValue());
                }
            }
        }
        return reasons.isEmpty() ? NONE : new TasteEvidence(reasons, Map.of());
    }

    /** 여행기 취향 신호를 더한다. 설문 근거 문장 뒤에 여행기 근거 문장을 붙인다. */
    public TasteEvidence withDiary(DiarySignalAffinity.Result diary) {
        if (diary.affinity().isEmpty()) {
            return this;
        }
        Map<AttractionCategory, List<String>> merged = new EnumMap<>(AttractionCategory.class);
        reasons.forEach((category, list) -> merged.put(category, new ArrayList<>(list)));
        diary.affinity().forEach((category, value) -> {
            String tag = diary.topTag().get(category);
            if (value >= DIARY_REASON_MIN_AFFINITY && tag != null) {
                add(merged, category, "지난 여행에서 좋았던 '" + tag + "'" + (hasFinalConsonant(tag) ? "과" : "와") + " 비슷해요");
            }
        });
        return new TasteEvidence(merged, Map.copyOf(diary.affinity()));
    }

    /** 여행기 신호의 유형 친화도 0~1. 신호가 없으면 0. */
    public double affinity(AttractionCategory category) {
        return affinity.getOrDefault(category, 0.0);
    }

    private static boolean hasFinalConsonant(String word) {
        char last = word.charAt(word.length() - 1);
        return last >= '가' && last <= '힣' && (last - '가') % 28 != 0;
    }

    private static void add(Map<AttractionCategory, List<String>> reasons, AttractionCategory category, String reason) {
        List<String> list = reasons.computeIfAbsent(category, c -> new ArrayList<>());
        if (!list.contains(reason)) {
            list.add(reason);
        }
    }

    /** 그 유형에 맞는 근거 문장들(우선순위 순). 없으면 빈 목록. */
    public List<String> reasonsFor(AttractionCategory category) {
        return Collections.unmodifiableList(reasons.getOrDefault(category, List.of()));
    }
}
