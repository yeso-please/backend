package com.yeso.backend.trip.domain;

/**
 * 두 장소 사이 이동시간(분) 추정. 직선거리 × 보정계수 ÷ 속도이며 실제 도로 시간이 아니다
 * (docs/api/trip.md 5장 travelFromPreviousMinutes). 도보 1.25·4km/h, 자동차 1.35·35km/h,
 * 대중교통 1.50·25km/h, 최소 5분.
 */
public final class TravelTimeEstimator {

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final int MIN_MINUTES = 5;

    private TravelTimeEstimator() {
    }

    public static int minutes(double fromLat, double fromLng, double toLat, double toLng, Transport transport) {
        double km = distanceKm(fromLat, fromLng, toLat, toLng);
        double factor;
        double kmPerHour;
        switch (transport) {
            case WALK -> {
                factor = 1.25;
                kmPerHour = 4;
            }
            case CAR -> {
                factor = 1.35;
                kmPerHour = 35;
            }
            case PUBLIC_TRANSIT -> {
                factor = 1.50;
                kmPerHour = 25;
            }
            default -> throw new IllegalArgumentException("알 수 없는 이동수단: " + transport);
        }
        return Math.max(MIN_MINUTES, (int) Math.round(km * factor / kmPerHour * 60));
    }

    /** 하버사인 직선거리(km). */
    public static double distanceKm(double fromLat, double fromLng, double toLat, double toLng) {
        double dLat = Math.toRadians(toLat - fromLat);
        double dLng = Math.toRadians(toLng - fromLng);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(fromLat)) * Math.cos(Math.toRadians(toLat))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }
}
