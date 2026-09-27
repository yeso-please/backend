package com.yeso.backend.trip.presentation.course;

import java.util.List;

public record AlternativeCoursesResponse(String itemId, List<Group> groups) {
    public record Group(String category, String label, List<Item> items) {
    }

    public record Item(
            Long attractionId, String name, String category, String thumbnailUrl,
            double lat, double lng, int estimatedDurationMinutes, Integer travelFromPreviousMinutes,
            String reason) {
    }
}
