package com.yeso.backend.trip.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 하루 동선의 구간 규칙(docs/api/trip.md 5장). 항목 순서대로 관광지와 식당을 모두 지점으로 잇고, 구간 값은 도착 항목에
 * 붙는다. 좌표가 없는 지점(식당 미정 등)에 닿는 앞뒤 구간은 만들지 않는다 — 그 지점을 건너뛰어 잇지 않는다.
 */
public final class TravelChain {

    /** 카카오 길찾기 한 요청의 최대 지점 수(출발 + 경유 5 + 도착). */
    public static final int MAX_POINTS_PER_REQUEST = 7;

    /** 길찾기는 출발·도착이 5m 안이면 경로를 거절한다. 여유를 두고 이 거리 안의 이웃 지점 사이에서 요청을 끊는다. */
    static final double MIN_ROUTABLE_KM = 0.010;

    private TravelChain() {
    }

    /** 지점. 좌표가 없으면 {@code lat}·{@code lng}가 null이다. {@code key}는 호출한 쪽이 항목을 찾는 값이다. */
    public record Stop<K>(K key, Double lat, Double lng) {
        public boolean located() {
            return lat != null && lng != null;
        }
    }

    /** 앞 지점에서 도착 지점으로 가는 구간. */
    public record Leg<K>(Stop<K> from, Stop<K> to) {
    }

    /** 하루 지점 목록(방문 순서)에서 이동값을 매길 구간들. */
    public static <K> List<Leg<K>> legs(List<Stop<K>> day) {
        List<Leg<K>> legs = new ArrayList<>();
        for (int i = 1; i < day.size(); i++) {
            Stop<K> from = day.get(i - 1);
            Stop<K> to = day.get(i);
            if (from.located() && to.located()) {
                legs.add(new Leg<>(from, to));
            }
        }
        return legs;
    }

    /**
     * 길찾기 요청 단위. 좌표가 이어지는 지점 묶음을 {@link #MAX_POINTS_PER_REQUEST}개 이하로 자르며, 잘린 경계 지점은 앞
     * 묶음의 끝이자 뒤 묶음의 시작이라 구간이 빠지지 않는다. 지점이 2개 미만인 묶음은 버린다. {@link #MIN_ROUTABLE_KM}
     * 안의 이웃 지점 사이에서도 끊는다(그 구간은 추정값으로 남는다).
     */
    public static <K> List<List<Stop<K>>> requests(List<Stop<K>> day) {
        List<List<Stop<K>>> runs = new ArrayList<>();
        List<Stop<K>> run = new ArrayList<>();
        for (Stop<K> stop : day) {
            if (!stop.located()) {
                runs.add(run);
                run = new ArrayList<>();
                continue;
            }
            if (!run.isEmpty() && tooClose(run.get(run.size() - 1), stop)) {
                runs.add(run);
                run = new ArrayList<>();
            }
            run.add(stop);
        }
        runs.add(run);

        List<List<Stop<K>>> requests = new ArrayList<>();
        for (List<Stop<K>> located : runs) {
            for (int start = 0; start + 1 < located.size(); start += MAX_POINTS_PER_REQUEST - 1) {
                requests.add(List.copyOf(located.subList(start, Math.min(located.size(), start + MAX_POINTS_PER_REQUEST))));
            }
        }
        return requests;
    }

    private static boolean tooClose(Stop<?> a, Stop<?> b) {
        return TravelTimeEstimator.distanceKm(a.lat(), a.lng(), b.lat(), b.lng()) < MIN_ROUTABLE_KM;
    }
}
