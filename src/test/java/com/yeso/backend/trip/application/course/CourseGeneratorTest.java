package com.yeso.backend.trip.application.course;

import com.yeso.backend.attraction.application.region.RegionEligibilityService.CourseCandidate;
import com.yeso.backend.attraction.domain.AttractionCategory;
import com.yeso.backend.trip.application.course.CourseGeneration.AttractionItem;
import com.yeso.backend.trip.application.course.CourseGeneration.Day;
import com.yeso.backend.trip.application.course.CourseGeneration.MealItem;
import com.yeso.backend.trip.application.course.CourseGeneration.OfficialCourse;
import com.yeso.backend.trip.application.course.CourseGeneration.Request;
import com.yeso.backend.trip.application.course.CourseGeneration.Result;
import com.yeso.backend.trip.application.course.CourseGeneration.Warning;
import com.yeso.backend.trip.domain.CourseInsufficientCandidatesException;
import com.yeso.backend.trip.domain.MealType;
import com.yeso.backend.trip.domain.RecommendationMode;
import com.yeso.backend.trip.domain.Transport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourseGeneratorTest {

    private final CourseGenerator generator = new CourseGenerator();

    private static CourseCandidate candidate(long id, AttractionCategory category, double lat, double lng) {
        return new CourseCandidate(id, "관광지" + id, category, 90, "주소", lat, lng, null);
    }

    /** 경주 근처에 흩어진 후보 {@code count}곳. 유형은 번갈아 가며. */
    private static List<CourseCandidate> candidates(int count) {
        List<CourseCandidate> list = new ArrayList<>();
        AttractionCategory[] categories = {AttractionCategory.HISTORY_CULTURE, AttractionCategory.NATURE};
        for (int i = 1; i <= count; i++) {
            list.add(candidate(i, categories[i % 2], 35.80 + i * 0.001, 129.20 + i * 0.001));
        }
        return list;
    }

    private static Request request(int days, String density, List<CourseCandidate> candidates) {
        return new Request("경주시", days, density, Transport.CAR, candidates, null, Map.of(), List.of(), false, null);
    }

    private static List<AttractionItem> attractions(Day day) {
        return day.items().stream().filter(AttractionItem.class::isInstance).map(AttractionItem.class::cast).toList();
    }

    private static Set<Long> selectedIds(Result result) {
        Set<Long> ids = new HashSet<>();
        result.days().forEach(day -> attractions(day).forEach(item -> ids.add(item.attraction().attractionId())));
        return ids;
    }

    @Nested
    @DisplayName("날짜별 구성")
    class Structure {

        @Test
        @DisplayName("여유 2일이면 하루 관광지 4곳, 점심은 2곳 뒤, 저녁은 맨 끝이다")
        void relaxedTwoDays() {
            Result result = generator.generate(request(2, "RELAXED", candidates(20)), new Random(1));

            assertThat(result.days()).hasSize(2);
            for (Day day : result.days()) {
                assertThat(attractions(day)).hasSize(4);
                assertThat(day.items()).hasSize(6);
                assertThat(day.items().get(2)).isEqualTo(new MealItem(MealType.LUNCH));
                assertThat(day.items().get(5)).isEqualTo(new MealItem(MealType.DINNER));
            }
        }

        @Test
        @DisplayName("빡빡 1일이면 관광지 6곳이다")
        void packedOneDay() {
            Result result = generator.generate(request(1, "PACKED", candidates(20)), new Random(1));

            assertThat(attractions(result.days().get(0))).hasSize(6);
        }

        @Test
        @DisplayName("관광지가 1곳뿐인 날도 점심·저녁이 모두 들어간다")
        void singleAttractionDay() {
            Result result = generator.generate(request(1, "RELAXED", candidates(1)), new Random(1));

            assertThat(result.days().get(0).items()).containsExactly(
                    result.days().get(0).items().get(0), new MealItem(MealType.LUNCH), new MealItem(MealType.DINNER));
        }

        @Test
        @DisplayName("이동시간은 그날 첫 관광지만 비어 있고, 점심 다음 관광지는 점심 앞 관광지에서 잰다")
        void travelMinutes() {
            Result result = generator.generate(request(1, "RELAXED", candidates(10)), new Random(1));
            List<AttractionItem> items = attractions(result.days().get(0));

            assertThat(items.get(0).travelMinutesFromPrevious()).isNull();
            assertThat(items.subList(1, items.size()))
                    .allSatisfy(item -> assertThat(item.travelMinutesFromPrevious()).isGreaterThanOrEqualTo(5));
        }
    }

    @Nested
    @DisplayName("후보가 모자랄 때")
    class Shortage {

        @Test
        @DisplayName("목표보다 적으면 있는 만큼 나눠 넣고 그날마다 DENSITY_TARGET_NOT_MET을 붙인다")
        void belowTarget() {
            Result result = generator.generate(request(2, "RELAXED", candidates(5)), new Random(1));

            assertThat(result.days()).extracting(day -> attractions(day).size()).containsExactly(3, 2);
            assertThat(result.warnings()).contains(
                    new Warning("DENSITY_TARGET_NOT_MET", 0), new Warning("DENSITY_TARGET_NOT_MET", 1));
        }

        @Test
        @DisplayName("하루 1곳도 못 채우는 날이 있으면 모자란 날을 담아 COURSE_INSUFFICIENT_CANDIDATES다")
        void insufficient() {
            assertThatThrownBy(() -> generator.generate(request(3, "RELAXED", candidates(1)), new Random(1)))
                    .isInstanceOfSatisfying(CourseInsufficientCandidatesException.class, e -> assertThat(e.getDetails().get("days"))
                            .asList().hasSize(2));
        }
    }

    @Nested
    @DisplayName("무작위와 유형 섞기")
    class Randomness {

        @Test
        @DisplayName("같은 시드면 같은 코스, 다른 시드면 다른 코스가 나온다")
        void seedDecidesCourse() {
            Request request = request(1, "RELAXED", candidates(30));

            assertThat(selectedIds(generator.generate(request, new Random(7))))
                    .isEqualTo(selectedIds(generator.generate(request, new Random(7))));
            Set<Set<Long>> variations = new HashSet<>();
            for (int seed = 0; seed < 10; seed++) {
                variations.add(selectedIds(generator.generate(request, new Random(seed))));
            }
            assertThat(variations.size()).isGreaterThan(5);
        }

        @Test
        @DisplayName("같은 유형만 몰리는 경우가 드물다")
        void mixesCategories() {
            List<CourseCandidate> list = new ArrayList<>();
            for (int i = 1; i <= 20; i++) {
                list.add(candidate(i, i <= 10 ? AttractionCategory.HISTORY_CULTURE : AttractionCategory.NATURE, 35.8, 129.2 + i * 0.001));
            }
            int allSame = 0;
            for (int seed = 0; seed < 200; seed++) {
                Result result = generator.generate(request(1, "RELAXED", list), new Random(seed));
                long categories = attractions(result.days().get(0)).stream().map(item -> item.attraction().category()).distinct().count();
                if (categories == 1) {
                    allSame++;
                }
            }
            assertThat(allSame).isLessThan(10); // 5% 미만
        }
    }

    @Nested
    @DisplayName("추천 모드")
    class Modes {

        @Test
        @DisplayName("취향 데이터가 없으면 규칙 코스이고 PERSONALIZATION_FALLBACK을 붙인다")
        void ruleBased() {
            Result result = generator.generate(request(1, "RELAXED", candidates(10)), new Random(1));

            assertThat(result.mode()).isEqualTo(RecommendationMode.RULE_BASED);
            assertThat(result.warnings()).contains(
                    new Warning("ROUTE_TIME_ESTIMATED", null), new Warning("PERSONALIZATION_FALLBACK", null));
        }

        @Test
        @DisplayName("취향 반영은 점수 상위 3배(여유 1일 = 12곳) 안에서만 고른다")
        void personalizedStaysInTopPool() {
            List<CourseCandidate> list = candidates(40);
            Map<Long, float[]> vectors = new HashMap<>();
            for (CourseCandidate c : list) {
                // ID가 작을수록 요청자 벡터(1, 0)와 비슷하다
                double angle = Math.toRadians(c.attractionId() * 2);
                vectors.put(c.attractionId(), new float[]{(float) Math.cos(angle), (float) Math.sin(angle)});
            }
            Request request = new Request("경주시", 1, "RELAXED", Transport.CAR, list, new float[]{1, 0}, vectors, List.of(), false, null);

            for (int seed = 0; seed < 30; seed++) {
                Result result = generator.generate(request, new Random(seed));
                assertThat(result.mode()).isEqualTo(RecommendationMode.PERSONALIZED);
                assertThat(selectedIds(result)).allSatisfy(id -> assertThat(id).isLessThanOrEqualTo(12));
            }
            Result result = generator.generate(request, new Random(1));
            assertThat(result.warnings()).doesNotContain(new Warning("PERSONALIZATION_FALLBACK", null));
            // 설문 근거가 없으면 취향 유사도 상위 3곳에만 이유가 붙고 나머지는 null이다
            assertThat(attractions(result.days().get(0))).extracting(AttractionItem::reason)
                    .containsOnly(CourseGenerator.SIMILAR_REASON, null)
                    .filteredOn(CourseGenerator.SIMILAR_REASON::equals).hasSize(CourseGenerator.SIMILAR_TOP);
        }

        @Test
        @DisplayName("벡터가 있는 관광지가 필요 수보다 적으면 취향 반영을 하지 않는다")
        void personalizedNeedsEnoughVectors() {
            List<CourseCandidate> list = candidates(10);
            Request request = new Request("경주시", 1, "RELAXED", Transport.CAR, list, new float[]{1, 0},
                    Map.of(1L, new float[]{1, 0}), List.of(), false, null);

            assertThat(generator.generate(request, new Random(1)).mode()).isEqualTo(RecommendationMode.RULE_BASED);
        }

        @Test
        @DisplayName("취향이 없고 공식 코스가 있으면 공식 코스 장소를 먼저 넣고 출처를 이유로 적는다")
        void tourOfficial() {
            List<CourseCandidate> list = candidates(20);
            OfficialCourse official = new OfficialCourse("신라 역사 탐방", List.of(3L, 7L, 999L));
            Request request = new Request("경주시", 1, "RELAXED", Transport.CAR, list, null, Map.of(), List.of(official), false, null);

            Result result = generator.generate(request, new Random(1));

            assertThat(result.mode()).isEqualTo(RecommendationMode.TOUR_OFFICIAL);
            assertThat(selectedIds(result)).contains(3L, 7L).doesNotContain(999L);
            assertThat(attractions(result.days().get(0)))
                    .filteredOn(item -> item.attraction().attractionId() == 3L)
                    .singleElement()
                    .satisfies(item -> assertThat(item.reason()).isEqualTo("관광공사 추천 코스 「신라 역사 탐방」에 나오는 곳이에요"));
            assertThat(result.warnings()).contains(new Warning("PERSONALIZATION_FALLBACK", null));
        }

        @Test
        @DisplayName("완전 랜덤을 고르면 취향 벡터·공식 코스가 있어도 RANDOM이고 대체 경고를 붙이지 않는다")
        void randomOnly() {
            List<CourseCandidate> list = candidates(20);
            Map<Long, float[]> vectors = new HashMap<>();
            list.forEach(c -> vectors.put(c.attractionId(), new float[]{1, 0}));
            Request request = new Request("경주시", 1, "RELAXED", Transport.CAR, list, new float[]{1, 0}, vectors,
                    List.of(new OfficialCourse("신라 역사 탐방", List.of(3L))), true, null);

            Result result = generator.generate(request, new Random(1));

            assertThat(result.mode()).isEqualTo(RecommendationMode.RANDOM);
            assertThat(result.warnings()).doesNotContain(new Warning("PERSONALIZATION_FALLBACK", null));
            assertThat(attractions(result.days().get(0))).allSatisfy(item -> assertThat(item.reason()).isNull());
        }

        @Test
        @DisplayName("공식 코스 장소가 하나도 추천 가능하지 않으면 규칙 코스다")
        void officialWithoutUsableStops() {
            Request request = new Request("경주시", 1, "RELAXED", Transport.CAR, candidates(10), null, Map.of(),
                    List.of(new OfficialCourse("없는 곳 코스", List.of(999L))), false, null);

            assertThat(generator.generate(request, new Random(1)).mode()).isEqualTo(RecommendationMode.RULE_BASED);
        }
    }

    @Nested
    @DisplayName("동선")
    class Route {

        @Test
        @DisplayName("멀리 떨어진 두 무리는 서로 다른 날로 나뉜다")
        void splitsFarGroups() {
            List<CourseCandidate> list = new ArrayList<>();
            for (int i = 1; i <= 4; i++) {
                list.add(candidate(i, AttractionCategory.NATURE, 35.80 + i * 0.002, 129.20));       // 경주 시내
                list.add(candidate(10 + i, AttractionCategory.NATURE, 35.70 + i * 0.002, 129.45));  // 동쪽 바닷가
            }

            Result result = generator.generate(request(2, "RELAXED", list), new Random(1));

            for (Day day : result.days()) {
                Set<Boolean> groups = new HashSet<>();
                attractions(day).forEach(item -> groups.add(item.attraction().attractionId() > 10));
                assertThat(groups).hasSize(1);
            }
        }

        @Test
        @DisplayName("하루 안에서는 한쪽 끝에서 시작해 가까운 순서로 잇는다")
        void ordersByNearest() {
            List<CourseCandidate> line = List.of(
                    candidate(1, AttractionCategory.NATURE, 35.80, 129.20),
                    candidate(2, AttractionCategory.NATURE, 35.80, 129.23),
                    candidate(3, AttractionCategory.NATURE, 35.80, 129.21),
                    candidate(4, AttractionCategory.NATURE, 35.80, 129.22));

            List<CourseCandidate> ordered = CourseGenerator.orderByNearest(line);

            List<Long> ids = ordered.stream().map(CourseCandidate::attractionId).toList();
            assertThat(ids).isIn(List.of(1L, 3L, 4L, 2L), List.of(2L, 4L, 3L, 1L));
        }
    }

    @Nested
    @DisplayName("제목")
    class Title {

        @Test
        @DisplayName("가장 많은 유형으로 '경주, 역사를 따라 걷는 2일'을 만든다")
        void themeTitle() {
            List<CourseCandidate> selected = List.of(
                    candidate(1, AttractionCategory.HISTORY_CULTURE, 35.8, 129.2),
                    candidate(2, AttractionCategory.HISTORY_CULTURE, 35.8, 129.2),
                    candidate(3, AttractionCategory.NATURE, 35.8, 129.2));

            assertThat(CourseGenerator.title("경주시", 2, selected)).isEqualTo("경주, 역사를 따라 걷는 2일");
        }

        @Test
        @DisplayName("대표 유형이 없으면 '경주에서 보내는 1일'이다")
        void fallbackTitle() {
            assertThat(CourseGenerator.title("경주시", 1, List.of(candidate(1, AttractionCategory.ETC, 35.8, 129.2))))
                    .isEqualTo("경주에서 보내는 1일");
        }

        @Test
        @DisplayName("지역 이름 끝의 시·군·구를 뗀다. 두 글자 이름은 그대로 둔다")
        void shortRegionName() {
            assertThat(CourseGenerator.shortRegionName("경주시")).isEqualTo("경주");
            assertThat(CourseGenerator.shortRegionName("양양군")).isEqualTo("양양");
            assertThat(CourseGenerator.shortRegionName("중구")).isEqualTo("중구");
        }
    }

    @Nested
    @DisplayName("추천 이유")
    class Reasons {

        private Request personalized(List<CourseCandidate> list, TasteEvidence evidence) {
            Map<Long, float[]> vectors = new HashMap<>();
            for (CourseCandidate c : list) {
                double angle = Math.toRadians(c.attractionId());
                vectors.put(c.attractionId(), new float[]{(float) Math.cos(angle), (float) Math.sin(angle)});
            }
            return new Request("경주시", 1, "PACKED", Transport.CAR, list, new float[]{1, 0}, vectors, List.of(), false,
                    evidence);
        }

        private List<CourseCandidate> sameCategory(int count, AttractionCategory category) {
            List<CourseCandidate> list = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                list.add(candidate(i, category, 35.80 + i * 0.001, 129.20 + i * 0.001));
            }
            return list;
        }

        @Test
        @DisplayName("설문 근거가 장소 유형과 맞으면 그 문장을 쓰고, 같은 문장은 코스 안에서 최대 2번이다")
        void evidenceSentence_atMostTwice() {
            TasteEvidence evidence = TasteEvidence.from(Map.of(1, 1, 3, 4, 5, 4, 6, 4), List.of());
            Result result = generator.generate(personalized(sameCategory(30, AttractionCategory.NATURE), evidence),
                    new Random(3));

            List<String> reasons = attractions(result.days().get(0)).stream().map(AttractionItem::reason).toList();
            assertThat(reasons).hasSize(6);
            assertThat(reasons).filteredOn(TasteEvidence.NATURE_STYLE::equals).hasSize(CourseGenerator.MAX_SAME_REASON);
            assertThat(reasons).containsOnly(TasteEvidence.NATURE_STYLE, CourseGenerator.SIMILAR_REASON, null);
        }

        @Test
        @DisplayName("근거가 여러 개면 순서대로 쓰고, 다 쓰면 다음 근거로 넘어간다")
        void evidenceSentence_movesToNextEvidence() {
            TasteEvidence evidence = TasteEvidence.from(Map.of(1, 4, 3, 4, 5, 6, 6, 4), List.of(6, 7));
            Result result = generator.generate(personalized(sameCategory(30, AttractionCategory.ACTIVITY), evidence),
                    new Random(3));

            List<String> reasons = attractions(result.days().get(0)).stream().map(AttractionItem::reason).toList();
            assertThat(reasons).filteredOn(TasteEvidence.ACTIVITY_STYLE::equals).hasSize(2);
            assertThat(reasons).filteredOn("'운동과 건강' 여행 동기와 맞아요"::equals).hasSize(2);
            assertThat(reasons).filteredOn("'새로운 경험' 여행 동기와 맞아요"::equals).hasSize(2);
        }

        @Test
        @DisplayName("기타 유형에는 설문 근거를 쓰지 않는다")
        void etc_hasNoEvidenceSentence() {
            TasteEvidence evidence = TasteEvidence.from(Map.of(1, 1, 3, 4, 5, 1, 6, 4), List.of(2, 6, 7, 8));
            Result result = generator.generate(personalized(sameCategory(30, AttractionCategory.ETC), evidence),
                    new Random(3));

            assertThat(attractions(result.days().get(0))).extracting(AttractionItem::reason)
                    .containsOnly(CourseGenerator.SIMILAR_REASON, null);
        }

        @Test
        @DisplayName("규칙 코스는 설문 근거가 있어도 이유를 붙이지 않는다")
        void ruleBased_hasNoReason() {
            TasteEvidence evidence = TasteEvidence.from(Map.of(1, 1, 3, 4, 5, 4, 6, 4), List.of());
            Request request = new Request("경주시", 1, "RELAXED", Transport.CAR, sameCategory(10, AttractionCategory.NATURE),
                    null, Map.of(), List.of(), false, evidence);

            Result result = generator.generate(request, new Random(1));

            assertThat(result.mode()).isEqualTo(RecommendationMode.RULE_BASED);
            assertThat(attractions(result.days().get(0))).allSatisfy(item -> assertThat(item.reason()).isNull());
        }
    }
}
