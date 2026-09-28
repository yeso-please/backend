package com.yeso.backend.attraction.application.region;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequiredAttractionsTest {

    @Test
    @DisplayName("필요한 추천 가능 관광지 수는 일수 × 밀도 상한 + min(일수, 3)이다")
    void requiredAttractions_formula() {
        assertThat(RegionEligibilityService.requiredAttractions(1, "RELAXED")).isEqualTo(5);
        assertThat(RegionEligibilityService.requiredAttractions(3, "PACKED")).isEqualTo(21);
        assertThat(RegionEligibilityService.requiredAttractions(7, "RELAXED")).isEqualTo(31);
    }

    @Test
    @DisplayName("밀도가 RELAXED·PACKED가 아니면 호출 오류다")
    void requiredAttractions_unknownDensity() {
        assertThatThrownBy(() -> RegionEligibilityService.requiredAttractions(1, "FAST"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RegionEligibilityService.requiredAttractions(1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
