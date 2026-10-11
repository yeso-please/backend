package com.yeso.backend.trip.domain;

import com.yeso.backend.trip.domain.TravelChain.Stop;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TravelChainTest {

    private static Stop<String> at(String key, double lat, double lng) {
        return new Stop<>(key, lat, lng);
    }

    private static Stop<String> empty(String key) {
        return new Stop<>(key, null, null);
    }

    @Test
    @DisplayName("구간은 이어진 두 지점이 모두 좌표를 가질 때만 생기고, 좌표 없는 지점을 건너뛰어 잇지 않는다")
    void legsSkipEmptyStopWithoutBridging() {
        List<TravelChain.Leg<String>> legs = TravelChain.legs(List.of(
                at("a", 35.80, 129.20), at("b", 35.81, 129.21), empty("meal"), at("c", 35.82, 129.22), at("d", 35.83, 129.23)));
        assertThat(legs).extracting(leg -> leg.from().key() + ">" + leg.to().key()).containsExactly("a>b", "c>d");
    }

    @Test
    @DisplayName("요청 묶음은 좌표 없는 지점에서 끊기고, 지점이 하나뿐인 묶음은 버린다")
    void requestsBreakAtEmptyStop() {
        List<List<Stop<String>>> requests = TravelChain.requests(List.of(
                at("a", 35.80, 129.20), empty("meal"), at("b", 35.81, 129.21), at("c", 35.82, 129.22)));
        assertThat(requests).extracting(r -> r.stream().map(Stop::key).toList())
                .containsExactly(List.of("b", "c"));
    }

    @Test
    @DisplayName("지점이 7개를 넘으면 경계 지점을 겹쳐 나눠 구간이 빠지지 않는다")
    void requestsSplitAtSevenPointsSharingBoundary() {
        List<Stop<String>> day = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            day.add(at("p" + i, 35.80 + i * 0.01, 129.20 + i * 0.01));
        }
        List<List<Stop<String>>> requests = TravelChain.requests(day);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0)).hasSize(7);
        assertThat(requests.get(1)).extracting(Stop::key).containsExactly("p6", "p7", "p8");
    }

    @Test
    @DisplayName("10m 안의 이웃 지점은 길찾기가 거절하므로 그 사이에서 요청을 끊는다")
    void requestsBreakBetweenNearlyIdenticalStops() {
        List<List<Stop<String>>> requests = TravelChain.requests(List.of(
                at("a", 35.80, 129.20), at("a2", 35.80001, 129.20001), at("b", 35.81, 129.21)));
        assertThat(requests).extracting(r -> r.stream().map(Stop::key).toList())
                .containsExactly(List.of("a2", "b"));
    }
}
