package com.yeso.backend.attraction.presentation.region;

import com.yeso.backend.attraction.application.region.RegionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "7. 지역·관광지", description = "docs/api/attraction.md §7")
@RestController
@RequiredArgsConstructor
public class AttractionController {

    private final RegionQueryService regionQueryService;

    @Operation(summary = "7-4 관광지 상세")
    @GetMapping("/api/attractions/{attractionId}")
    public AttractionDetailResponse detail(@PathVariable Long attractionId) {
        return regionQueryService.attractionDetail(attractionId);
    }
}
