package com.yeso.backend.trip.presentation.course;

import com.yeso.backend.shared.web.CurrentUserId;
import com.yeso.backend.trip.application.course.RestaurantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "5. 코스", description = "docs/api/trip.md §5")
@RestController
@RequestMapping("/api/courses/{tripId}/restaurants")
@RequiredArgsConstructor
public class RestaurantController {

    private final RestaurantService restaurantService;

    @Operation(summary = "5-6 식당 검색(카카오 Local)")
    @GetMapping("/search")
    public RestaurantSearchResponse search(
            @CurrentUserId Long userId, @PathVariable Long tripId, @Valid @ModelAttribute RestaurantSearchRequest request) {
        return restaurantService.search(
                userId, tripId, request.itemId(), request.query(), request.radiusOrDefault(), request.pageOrDefault());
    }
}
