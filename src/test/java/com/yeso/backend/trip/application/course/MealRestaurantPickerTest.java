package com.yeso.backend.trip.application.course;

import com.yeso.backend.attraction.application.region.RestaurantQueryService.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class MealRestaurantPickerTest {

    private final MealRestaurantPicker picker = new MealRestaurantPicker();

    /** 거리가 가까운 순으로 r1, r2, … 를 만든다. */
    private static List<Candidate> candidates(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> new Candidate("r" + i, "식당" + i, null, null, null, null, 0, 0, null, null, null,
                        null, null, null, i * 100))
                .toList();
    }

    /** {@code nextInt(bound)}가 항상 {@code value}(bound 안으로 줄여서)를 돌려주는 난수. */
    private static RandomGenerator fixed(int value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return value;
            }

            @Override
            public int nextInt(int bound) {
                return Math.min(value, bound - 1);
            }
        };
    }

    @Test
    @DisplayName("가까운 순 상위 10개 중에서만 고른다")
    void picksOnlyFromNearestTen() {
        List<Candidate> picked = IntStream.range(0, 11)
                .mapToObj(i -> picker.pick(candidates(30), Set.of(), fixed(i)).orElseThrow())
                .toList();

        assertThat(picked).extracting(Candidate::externalId)
                .containsOnly("r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8", "r9", "r10");
    }

    @Test
    @DisplayName("난수가 같으면 같은 식당이 나온다")
    void sameRandomGivesSameRestaurant() {
        assertThat(picker.pick(candidates(30), Set.of(), fixed(3)).orElseThrow().externalId()).isEqualTo("r4");
        assertThat(picker.pick(candidates(30), Set.of(), fixed(3)).orElseThrow().externalId()).isEqualTo("r4");
    }

    @Test
    @DisplayName("이미 배정한 식당은 빼고 그다음 가까운 식당까지 상위 10개를 채운다")
    void skipsAlreadyPickedAndRefillsTopTen() {
        Set<String> used = Set.of("r1", "r2");

        assertThat(picker.pick(candidates(30), used, fixed(0)).orElseThrow().externalId()).isEqualTo("r3");
        assertThat(picker.pick(candidates(30), used, fixed(9)).orElseThrow().externalId()).isEqualTo("r12");
    }

    @Test
    @DisplayName("후보가 10개보다 적으면 있는 것 중에서 고른다")
    void fewerThanTenCandidates() {
        assertThat(picker.pick(candidates(3), Set.of(), fixed(99)).orElseThrow().externalId()).isEqualTo("r3");
    }

    @Test
    @DisplayName("후보가 없거나 모두 이미 배정됐으면 비어 있다")
    void emptyWhenNothingLeft() {
        assertThat(picker.pick(List.of(), Set.of(), fixed(0))).isEmpty();
        assertThat(picker.pick(candidates(2), Set.of("r1", "r2"), fixed(0))).isEmpty();
    }
}
