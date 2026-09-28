package com.yeso.backend.trip.presentation.course;

import com.yeso.backend.shared.web.CurrentUserId;
import com.yeso.backend.trip.application.course.CourseService;
import com.yeso.backend.trip.application.course.CourseEditService;
import com.yeso.backend.trip.application.course.CourseAlternativesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "5. 코스", description = "docs/api/trip.md §5")
@RestController
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;
    private final CourseEditService courseEditService;
    private final CourseAlternativesService alternativesService;

    @Operation(summary = "5-1 코스 생성·재생성")
    @PostMapping("/api/courses/{tripId}/generate")
    public ResponseEntity<CourseResponse> generate(
            @CurrentUserId Long userId, @PathVariable Long tripId, @Valid @RequestBody GenerateCourseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(courseService.generate(userId, tripId, request));
    }

    @Operation(summary = "5-2 코스 조회")
    @GetMapping("/api/courses/{tripId}")
    public CourseResponse get(@CurrentUserId Long userId, @PathVariable Long tripId) {
        return courseService.getCourse(userId, tripId);
    }

    @Operation(summary = "5-3 일정 편집")
    @PatchMapping("/api/courses/{tripId}/schedule")
    public CourseResponse edit(@CurrentUserId Long userId, @PathVariable Long tripId,
                               @Valid @RequestBody EditCourseRequest request) {
        return courseEditService.edit(userId, tripId, request);
    }

    @Operation(summary = "5-4 대체·추가 후보")
    @GetMapping("/api/courses/{tripId}/alternatives")
    public AlternativeCoursesResponse alternatives(@CurrentUserId Long userId, @PathVariable Long tripId,
                                                    @RequestParam(required = false) String itemId,
                                                    @RequestParam(required = false) String category,
                                                    @RequestParam(required = false) Integer limit,
                                                    @RequestParam(required = false) String q) {
        return alternativesService.alternatives(userId, tripId, itemId, category, limit, q);
    }
}
