package com.yeso.backend.attraction.domain;

/**
 * 관광지 유형과 기본 체류 시간(docs/api/attraction.md 7장 "원천 분류 → category·기본 체류시간").
 * 명세는 TourAPI 분류코드(cat1·cat2)로 정하지만 그 코드는 데이터 보강(#50)에서 수집한다.
 * 그 전까지는 contentTypeId로 근사한다: 문화시설(14) → 역사·문화 120분, 레포츠(28) → 체험 120분, 나머지 → 기타 90분.
 */
public enum AttractionCategory {
    NATURE(90),
    HISTORY_CULTURE(90),
    ACTIVITY(90),
    WALK_REST(90),
    ETC(90);

    private static final int CULTURE_FACILITY = 14;
    private static final int LEISURE_SPORTS = 28;
    private static final int LONG_STAY_MINUTES = 120;

    private final int defaultStayMinutes;

    AttractionCategory(int defaultStayMinutes) {
        this.defaultStayMinutes = defaultStayMinutes;
    }

    public static AttractionCategory fromContentType(Integer contentTypeId) {
        if (contentTypeId == null) {
            return ETC;
        }
        return switch (contentTypeId) {
            case CULTURE_FACILITY -> HISTORY_CULTURE;
            case LEISURE_SPORTS -> ACTIVITY;
            default -> ETC;
        };
    }

    /** 문화시설·레포츠는 대형 시설이라 120분, 나머지는 유형 기본값. */
    public static int stayMinutesOf(Integer contentTypeId) {
        if (contentTypeId != null && (contentTypeId == CULTURE_FACILITY || contentTypeId == LEISURE_SPORTS)) {
            return LONG_STAY_MINUTES;
        }
        return fromContentType(contentTypeId).defaultStayMinutes;
    }
}
