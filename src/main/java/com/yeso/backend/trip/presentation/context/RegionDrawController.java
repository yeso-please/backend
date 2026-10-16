package com.yeso.backend.trip.presentation.context;

import com.yeso.backend.shared.web.CurrentUserId;
import com.yeso.backend.trip.application.context.RegionDrawService;
import com.yeso.backend.trip.application.context.RegionMatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 지역을 먼저 고르는 흐름(D1). 요청자의 취향·설문 밀도를 쓰므로 trip 모듈이 맡는다. */
@Tag(name = "7. 지역·관광지", description = "docs/api/attraction.md §7-5·7-6")
@RestController
@RequiredArgsConstructor
public class RegionDrawController {

    private final RegionDrawService regionDrawService;
    private final RegionMatchService regionMatchService;

    @Operation(summary = "7-6 성향 맞춤 지역 문장")
    @GetMapping("/api/regions/{sigCd}/match")
    public RegionMatchResponse match(@CurrentUserId Long userId, @PathVariable String sigCd) {
        return regionMatchService.match(userId, sigCd);
    }

    @Operation(summary = "7-5 지역 먼저 뽑기(저장 없음)")
    @PostMapping("/api/regions/draw")
    public PreDrawRegionResponse draw(@CurrentUserId Long userId, @RequestBody PreDrawRegionRequest request) {
        return regionDrawService.preDraw(userId, request);
    }
}
