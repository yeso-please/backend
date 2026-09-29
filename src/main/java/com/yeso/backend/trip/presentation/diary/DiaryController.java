package com.yeso.backend.trip.presentation.diary;

import com.yeso.backend.shared.web.CurrentUserId;
import com.yeso.backend.trip.application.diary.DiaryService;
import com.yeso.backend.trip.application.diary.DiaryQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "6. 여행기·사진 지도", description = "docs/api/trip.md §6")
@RestController
@RequiredArgsConstructor
public class DiaryController {

    private final DiaryService diaryService;
    private final DiaryQueryService diaryQueryService;

    @Operation(summary = "6-1 여행기 만들기")
    @PostMapping("/api/courses/{tripId}/diary")
    public ResponseEntity<DiaryResponse> create(
            @CurrentUserId Long userId, @PathVariable Long tripId,
            @Valid @RequestBody(required = false) CreateDiaryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(diaryService.create(userId, tripId, request));
    }

    @Operation(summary = "6-2 여행기 사진 올리기")
    @PostMapping(value = "/api/diaries/{diaryId}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DiaryPhotosResponse> upload(
            @CurrentUserId Long userId, @PathVariable Long diaryId,
            @RequestPart("files") List<MultipartFile> files) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new DiaryPhotosResponse(diaryService.uploadPhotos(userId, diaryId, files)));
    }

    @Operation(summary = "6-3 여행기 사진 삭제")
    @DeleteMapping("/api/diaries/{diaryId}/photos/{photoId}")
    public ResponseEntity<Void> deletePhoto(
            @CurrentUserId Long userId, @PathVariable Long diaryId, @PathVariable Long photoId) {
        diaryService.deletePhoto(userId, diaryId, photoId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "6-4 여행기 수정")
    @PatchMapping("/api/diaries/{diaryId}")
    public DiaryResponse update(@CurrentUserId Long userId, @PathVariable Long diaryId,
                                @Valid @RequestBody UpdateDiaryRequest request) {
        return diaryService.update(userId, diaryId, request);
    }

    @Operation(summary = "6-6 여행기 조회")
    @org.springframework.web.bind.annotation.GetMapping("/api/diaries/{diaryId}")
    public DiaryResponse get(@CurrentUserId Long userId, @PathVariable Long diaryId) {
        return diaryQueryService.get(userId, diaryId);
    }

    @Operation(summary = "6-5 여행기 발행")
    @PostMapping("/api/diaries/{diaryId}/publish")
    public DiaryResponse publish(@CurrentUserId Long userId, @PathVariable Long diaryId) {
        return diaryService.publish(userId, diaryId);
    }

    @Operation(summary = "6-13 여행기 삭제")
    @DeleteMapping("/api/diaries/{diaryId}")
    public ResponseEntity<Void> delete(@CurrentUserId Long userId, @PathVariable Long diaryId) {
        diaryService.delete(userId, diaryId);
        return ResponseEntity.noContent().build();
    }
}
