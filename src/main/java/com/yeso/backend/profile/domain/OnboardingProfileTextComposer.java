package com.yeso.backend.profile.domain;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 임베딩 입력 텍스트를 결정적으로 합성한다. 순서는 항상 MBTI → 일정 밀도 → 정렬된 경험 태그 →
 * 제외 태그 → SIG_CD 오름차순 liked trip이다(같은 입력이면 같은 텍스트여야 임베딩도 안정적이다).
 * 자유서술(note)은 여기서만 쓰이고 로그에는 남기지 않는다(호출부 책임).
 */
public final class OnboardingProfileTextComposer {

    private OnboardingProfileTextComposer() {
    }

    public static String compose(
            String mbtiCode,
            ScheduleDensity scheduleDensity,
            List<String> experienceTags,
            List<String> excludeTags,
            List<LikedTripInput> likedTrips
    ) {
        String sortedExperience = experienceTags.stream().sorted().collect(Collectors.joining(", "));
        String sortedExclude = excludeTags.stream().sorted().collect(Collectors.joining(", "));
        String likedTripsText = likedTrips.stream()
                .sorted(Comparator.comparing(LikedTripInput::sigCd))
                .map(OnboardingProfileTextComposer::formatLikedTrip)
                .collect(Collectors.joining("; "));

        return "MBTI: " + mbtiCode
                + "\n일정 밀도: " + scheduleDensity
                + "\n선호 경험: " + sortedExperience
                + "\n제외 조건: " + sortedExclude
                + "\n좋았던 여행지: " + likedTripsText;
    }

    private static String formatLikedTrip(LikedTripInput trip) {
        String tags = String.join(",", trip.tags());
        String note = trip.note() == null ? "" : trip.note();
        return trip.sigCd() + "(" + tags + ")" + (note.isBlank() ? "" : ":" + note);
    }

    public record LikedTripInput(String sigCd, String note, List<String> tags) {
    }
}
