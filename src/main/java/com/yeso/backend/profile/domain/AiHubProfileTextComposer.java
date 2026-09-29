package com.yeso.backend.profile.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** AI 서비스의 traveler_text와 같은 형식으로 취향 요약을 저장한다. AI 요청 자체는 구조화 데이터를 보낸다. */
public final class AiHubProfileTextComposer {

    private AiHubProfileTextComposer() {}

    public static String compose(Map<Integer, Integer> styles, List<Integer> motives, List<String> regions) {
        List<String> sentences = new ArrayList<>();
        List<String> preferences = new ArrayList<>();
        OnboardingQuestionBank.TRAVEL_STYLE_QUESTIONS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    int number = entry.getKey();
                    Integer value = styles.get(number);
                    if (value == null || value == 4 || value < 1 || value > 7) {
                        return;
                    }
                    String pole = value < 4 ? entry.getValue().leftPole() : entry.getValue().rightPole();
                    String particle = hasFinalConsonant(pole) ? "을" : "를";
                    String degree = switch (value) {
                        case 1, 7 -> "매우";
                        case 2, 6 -> "꽤";
                        default -> "약간";
                    };
                    preferences.add(pole + particle + " " + degree + " 선호");
                });
        if (!preferences.isEmpty()) {
            sentences.add(String.join(", ", preferences) + "하는 여행자.");
        }

        List<String> motiveLabels = motives.stream()
                .map(OnboardingQuestionBank.TRAVEL_MOTIVES::get)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        if (!motiveLabels.isEmpty()) {
            sentences.add("여행에서 원하는 것은 " + String.join(", ", motiveLabels) + ".");
        }
        List<String> uniqueRegions = regions.stream().filter(region -> region != null && !region.isBlank()).distinct().toList();
        if (!uniqueRegions.isEmpty()) {
            sentences.add("좋아하는 여행지는 " + String.join(", ", uniqueRegions) + ".");
        }
        return String.join(" ", sentences);
    }

    private static boolean hasFinalConsonant(String word) {
        char last = word.charAt(word.length() - 1);
        return last >= '가' && last <= '힣' && (last - '가') % 28 != 0;
    }
}
