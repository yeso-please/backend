package com.yeso.backend.attraction.presentation.region;

import com.yeso.backend.attraction.application.region.RegionQueryService;
import com.yeso.backend.attraction.domain.AttractionCategory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "7. 지역·관광지", description = "docs/api/attraction.md §7")
@RestController
@RequiredArgsConstructor
public class RegionController {

    private final RegionQueryService regionQueryService;

    @Operation(summary = "7-1 지역 목록")
    @GetMapping("/api/regions")
    public RegionListResponse list(
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false) String scheduleDensity) {
        return regionQueryService.listRegions(days, scheduleDensity);
    }

    @Operation(summary = "7-2 지역 카드")
    @GetMapping("/api/regions/{sigCd}/card")
    public RegionCardResponse card(@PathVariable String sigCd) {
        return regionQueryService.regionCard(sigCd);
    }

    @Operation(summary = "7-3 지도 관광지 핀")
    @GetMapping("/api/regions/{sigCd}/attractions")
    public AttractionPinsResponse pins(
            @PathVariable String sigCd,
            @RequestParam(required = false) String bbox,
            @RequestParam(required = false) AttractionCategory category,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return regionQueryService.pins(sigCd, bbox, category, cursor, limit);
    }
}
