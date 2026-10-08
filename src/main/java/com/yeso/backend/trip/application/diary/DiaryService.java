package com.yeso.backend.trip.application.diary;

import com.yeso.backend.auth.domain.User;
import com.yeso.backend.auth.domain.UserNotFoundException;
import com.yeso.backend.auth.infrastructure.UserRepository;
import com.yeso.backend.profile.domain.OnboardingQuestionBank;
import com.yeso.backend.shared.exception.ErrorCode;
import com.yeso.backend.trip.application.context.TripService;
import com.yeso.backend.trip.domain.DiaryException;
import com.yeso.backend.trip.domain.DiaryLocationPrecision;
import com.yeso.backend.trip.domain.DiaryPhoto;
import com.yeso.backend.trip.domain.DiaryStatus;
import com.yeso.backend.trip.domain.DiaryTasteSignal;
import com.yeso.backend.trip.domain.DiaryVisibility;
import com.yeso.backend.trip.domain.TravelDiary;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.infrastructure.DiaryPhotoRepository;
import com.yeso.backend.trip.infrastructure.DiaryPhotoStorage;
import com.yeso.backend.trip.infrastructure.DiaryTasteSignalRepository;
import com.yeso.backend.trip.infrastructure.TravelDiaryRepository;
import com.yeso.backend.trip.infrastructure.DiaryShareLinkRepository;
import com.yeso.backend.trip.presentation.diary.CreateDiaryRequest;
import com.yeso.backend.trip.presentation.diary.DiaryPhotoResponse;
import com.yeso.backend.trip.presentation.diary.DiaryResponse;
import com.yeso.backend.trip.presentation.diary.UpdateDiaryRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
@Transactional
public class DiaryService {

    public static final int MAX_PHOTOS = 30;
    private static final Duration PHOTO_URL_LIFETIME = Duration.ofMinutes(5);

    private final TravelDiaryRepository diaryRepository;
    private final DiaryShareLinkRepository shareLinkRepository;
    private final DiaryPhotoRepository photoRepository;
    private final DiaryTasteSignalRepository tasteSignalRepository;
    private final UserRepository userRepository;
    private final TripService tripService;
    private final DiaryPhotoStorage photoStorage;
    private final DiaryPhotoProcessor photoProcessor;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public DiaryResponse create(Long userId, Long tripId, CreateDiaryRequest request) {
        TripPlan trip = tripService.requireParticipantTripForUpdate(userId, tripId);
        if (!trip.isEnded(LocalDate.now(clock))) {
            throw error(ErrorCode.TRIP_NOT_ENDED, "여행 종료일이 지난 뒤 여행기를 작성할 수 있습니다.");
        }
        if (diaryRepository.existsByTripIdAndAuthorId(tripId, userId)) {
            TravelDiary existing = diaryRepository.findByTripIdAndAuthorId(tripId, userId).orElseThrow();
            throw new DiaryException(ErrorCode.DIARY_ALREADY_EXISTS_FOR_TRIP,
                    "이 여행에 작성한 여행기가 이미 있습니다.", Map.of("diaryId", existing.getId()));
        }
        User author = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        String courseTitle = trip.getTitle() == null || trip.getTitle().isBlank()
                ? trip.displayTitle() : trip.getTitle();
        String title = request == null || request.title() == null || request.title().isBlank()
                ? courseTitle : request.title().trim();
        TravelDiary diary = new TravelDiary(tripId, author, title, courseTitle,
                trip.getRegion() == null ? null : trip.getRegion().getSigCd(), trip.getStartDate(), trip.getEndDate());
        try {
            diaryRepository.saveAndFlush(diary);
        } catch (DataIntegrityViolationException exception) {
            TravelDiary existing = diaryRepository.findByTripIdAndAuthorId(tripId, userId).orElse(null);
            throw new DiaryException(ErrorCode.DIARY_ALREADY_EXISTS_FOR_TRIP,
                    "이 여행에 작성한 여행기가 이미 있습니다.", existing == null ? Map.of() : Map.of("diaryId", existing.getId()));
        }
        return response(userId, diary, true);
    }

    public List<DiaryPhotoResponse> uploadPhotos(Long userId, Long diaryId, List<MultipartFile> files) {
        TravelDiary diary = requireOwner(userId, diaryId);
        if (files == null || files.isEmpty()) throw error(ErrorCode.DIARY_PHOTO_INVALID, "업로드할 사진이 없습니다.");
        List<DiaryPhoto> existing = photoRepository.findByDiaryIdOrderByOrderIndexAscIdAsc(diaryId);
        if (existing.size() + files.size() > MAX_PHOTOS) {
            throw error(ErrorCode.DIARY_PHOTO_LIMIT_EXCEEDED, "여행기 사진은 최대 30장까지 올릴 수 있습니다.");
        }

        List<ProcessedDiaryPhoto> processed = new ArrayList<>();
        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            try {
                if (file == null || file.isEmpty() || file.getSize() > DiaryPhotoProcessor.MAX_FILE_SIZE) {
                    throw error(ErrorCode.DIARY_PHOTO_INVALID, "파일 크기가 허용 범위를 벗어났습니다.", Map.of("fileIndex", index));
                }
                processed.add(photoProcessor.process(file.getBytes(), file.getContentType()));
            } catch (DiaryException exception) {
                throw new DiaryException(exception.getErrorCode(), exception.getMessage(), Map.of("fileIndex", index));
            } catch (IOException exception) {
                throw error(ErrorCode.DIARY_PHOTO_INVALID, "사진 파일을 읽을 수 없습니다.", Map.of("fileIndex", index));
            }
        }

        List<DiaryPhoto> uploaded = new ArrayList<>();
        List<String> storedKeys = new ArrayList<>();
        try {
            for (int i = 0; i < processed.size(); i++) {
                ProcessedDiaryPhoto image = processed.get(i);
                String prefix = "diaries/" + userId + "/" + diaryId + "/" + UUID.randomUUID();
                String originalKey = prefix + "." + extension(image.contentType());
                String thumbnailKey = prefix + "-thumb.jpg";
                photoStorage.put(originalKey, image.original(), image.contentType());
                storedKeys.add(originalKey);
                photoStorage.put(thumbnailKey, image.thumbnail(), "image/jpeg");
                storedKeys.add(thumbnailKey);
                uploaded.add(new DiaryPhoto(diary, originalKey, thumbnailKey, image.contentType(),
                        image.original().length, image.takenAt(), image.latitude(), image.longitude(), existing.size() + i));
            }
            List<DiaryPhoto> saved = photoRepository.saveAllAndFlush(uploaded);
            if (diary.getCoverPhotoId() == null && !saved.isEmpty()) diary.setCoverPhotoId(saved.getFirst().getId());
            return saved.stream().map(this::photoResponse).toList();
        } catch (RuntimeException exception) {
            storedKeys.forEach(this::quietDelete);
            throw exception;
        }
    }

    public void deletePhoto(Long userId, Long diaryId, Long photoId) {
        TravelDiary diary = requireOwner(userId, diaryId);
        DiaryPhoto photo = photoRepository.findByIdAndDiaryId(photoId, diaryId)
                .orElseThrow(() -> error(ErrorCode.DIARY_PHOTO_NOT_FOUND, "여행기 사진을 찾을 수 없습니다."));
        List<DiaryPhoto> photos = photoRepository.findByDiaryIdOrderByOrderIndexAscIdAsc(diaryId);
        String objectKey = photo.getObjectKey();
        String thumbnailKey = photo.getThumbnailKey();
        photoRepository.delete(photo);
        photoRepository.flush();
        photos.stream().filter(candidate -> !candidate.getId().equals(photoId)).findFirst()
                .ifPresentOrElse(next -> {
                    if (photoId.equals(diary.getCoverPhotoId())) diary.setCoverPhotoId(next.getId());
                }, () -> diary.setCoverPhotoId(null));
        photoStorage.delete(objectKey);
        photoStorage.delete(thumbnailKey);
    }

    public DiaryResponse update(Long userId, Long diaryId, UpdateDiaryRequest request) {
        TravelDiary diary = requireOwner(userId, diaryId);
        String title = request.title() == null ? null : request.title().trim();
        if (title != null && title.isBlank()) throw error(ErrorCode.INVALID_REQUEST, "제목은 비워 둘 수 없습니다.");
        String body = request.body() == null ? null : request.body().trim();
        DiaryVisibility visibility = enumValue(request.visibility(), DiaryVisibility.class);
        DiaryLocationPrecision precision = enumValue(request.locationPrecision(), DiaryLocationPrecision.class);
        String tagsJson = request.experienceTags() == null ? null : experienceTagsJson(request.experienceTags());
        List<DiaryPhoto> photos = photoRepository.findByDiaryIdOrderByOrderIndexAscIdAsc(diaryId);
        applyPhotoOrder(request.photoOrder(), photos);
        if (request.coverPhotoId() != null && photos.stream().noneMatch(p -> p.getId().equals(request.coverPhotoId()))) {
            throw error(ErrorCode.DIARY_PHOTO_NOT_FOUND, "대표 사진은 이 여행기의 사진이어야 합니다.");
        }
        diary.update(title, body, visibility, precision, request.satisfaction(), tagsJson,
                request.includeInTasteProfile(), request.coverPhotoId());
        syncTasteSignal(diary);
        return response(userId, diary, true);
    }

    public DiaryResponse publish(Long userId, Long diaryId) {
        TravelDiary diary = requireOwner(userId, diaryId);
        if (diary.getStatus() == DiaryStatus.PUBLISHED) return response(userId, diary, true);
        List<String> missing = new ArrayList<>();
        if (diary.getTitle() == null || diary.getTitle().isBlank()) missing.add("TITLE");
        if (!missing.isEmpty()) {
            throw error(ErrorCode.DIARY_NOT_PUBLISHABLE, "여행기를 발행할 수 없습니다.", Map.of("missing", missing));
        }
        diary.publish(LocalDateTime.now(clock));
        syncTasteSignal(diary);
        return response(userId, diary, true);
    }

    public void delete(Long userId, Long diaryId) {
        TravelDiary diary = requireOwner(userId, diaryId);
        List<DiaryPhoto> photos = photoRepository.findByDiaryIdOrderByOrderIndexAscIdAsc(diaryId);
        shareLinkRepository.deleteAll(shareLinkRepository.findByDiaryIdOrderByCreatedAtDesc(diaryId));
        // The rows are removed in the same transaction. Best-effort object cleanup happens after authorization.
        for (DiaryPhoto photo : photos) {
            photoStorage.delete(photo.getObjectKey());
            photoStorage.delete(photo.getThumbnailKey());
        }
        photoRepository.deleteAll(photos);
        photoRepository.flush();
        tasteSignalRepository.deleteById(diaryId);
        diaryRepository.delete(diary);
    }

    public void changeVisibility(Long userId, Long diaryId, DiaryVisibility visibility) {
        TravelDiary diary = requireOwner(userId, diaryId);
        diary.updateForSharing(visibility);
    }

    @Transactional(readOnly = true)
    public DiaryResponse getForOwner(Long userId, Long diaryId) {
        return response(userId, requireOwner(userId, diaryId), true);
    }

    public TravelDiary requireOwnerDiary(Long userId, Long diaryId) {
        return requireOwner(userId, diaryId);
    }

    public DiaryResponse responseFor(Long viewerId, TravelDiary diary, boolean owner) {
        DiaryLocationPrecision precision = owner ? DiaryLocationPrecision.EXACT : diary.getLocationPrecision();
        return responseAtPrecision(viewerId, diary, owner, precision, null, null);
    }

    public DiaryResponse responseAtPrecision(Long viewerId, TravelDiary diary, boolean owner,
                                             DiaryLocationPrecision precision, Double fallbackLat, Double fallbackLng) {
        List<DiaryPhotoResponse> photos = photoRepository.findByDiaryIdOrderByOrderIndexAscIdAsc(diary.getId()).stream()
                .map(photo -> new DiaryPhotoResponse(photo.getId(),
                        photoStorage.presignedGet(photo.getObjectKey(), PHOTO_URL_LIFETIME),
                        photoStorage.presignedGet(photo.getThumbnailKey(), PHOTO_URL_LIFETIME), photo.getTakenAt(),
                        precision == DiaryLocationPrecision.EXACT ? photo.getLatitude()
                                : precision == DiaryLocationPrecision.CITY ? fallbackLat : null,
                        precision == DiaryLocationPrecision.EXACT ? photo.getLongitude()
                                : precision == DiaryLocationPrecision.CITY ? fallbackLng : null, photo.getOrderIndex()))
                .toList();
        Double lat = fallbackLat;
        Double lng = fallbackLng;
        if (precision == DiaryLocationPrecision.EXACT) {
            DiaryPhoto cover = photoRepository.findByDiaryIdOrderByOrderIndexAscIdAsc(diary.getId()).stream()
                    .filter(photo -> photo.getId().equals(diary.getCoverPhotoId())).findFirst().orElse(null);
            lat = cover == null ? null : cover.getLatitude();
            lng = cover == null ? null : cover.getLongitude();
        }
        return new DiaryResponse(diary.getId(), diary.getTripId(), diary.getStatus(), diary.getTitle(), diary.getBody(),
                diary.getCourseTitle(), diary.getRegionSigCd(), diary.getVisitedFrom(), diary.getVisitedTo(),
                diary.getVisibility(), precision, diary.getSatisfaction(), parseTags(diary.getExperienceTagsJson()),
                owner ? diary.isIncludeInTasteProfile() : null, diary.getCoverPhotoId(), photos,
                diary.getPublishedAt(), diary.getUpdatedAt());
    }

    public DiaryPhotoResponse coverResponse(TravelDiary diary) {
        return photoRepository.findByDiaryIdOrderByOrderIndexAscIdAsc(diary.getId()).stream()
                .filter(photo -> photo.getId().equals(diary.getCoverPhotoId())).findFirst()
                .map(this::photoResponse).orElse(null);
    }

    private void syncTasteSignal(TravelDiary diary) {
        if (diary.getStatus() != DiaryStatus.PUBLISHED || !diary.isIncludeInTasteProfile()) {
            tasteSignalRepository.deleteById(diary.getId());
            return;
        }
        tasteSignalRepository.save(new DiaryTasteSignal(diary.getId(), diary.getAuthor(), diary.getRegionSigCd(),
                diary.getSatisfaction(), diary.getExperienceTagsJson()));
    }

    private DiaryResponse response(Long userId, TravelDiary diary, boolean includeTasteSignal) {
        List<DiaryPhotoResponse> photos = photoRepository.findByDiaryIdOrderByOrderIndexAscIdAsc(diary.getId()).stream()
                .map(this::photoResponse).toList();
        return DiaryResponse.of(diary, photos, parseTags(diary.getExperienceTagsJson()), includeTasteSignal);
    }

    private DiaryPhotoResponse photoResponse(DiaryPhoto photo) {
        return new DiaryPhotoResponse(photo.getId(), photoStorage.presignedGet(photo.getObjectKey(), PHOTO_URL_LIFETIME),
                photoStorage.presignedGet(photo.getThumbnailKey(), PHOTO_URL_LIFETIME), photo.getTakenAt(),
                photo.getLatitude(), photo.getLongitude(), photo.getOrderIndex());
    }

    private TravelDiary requireOwner(Long userId, Long diaryId) {
        return diaryRepository.findByIdAndAuthorId(diaryId, userId)
                .orElseThrow(() -> error(ErrorCode.DIARY_NOT_FOUND, "여행기를 찾을 수 없습니다."));
    }

    private void applyPhotoOrder(List<Long> requestedOrder, List<DiaryPhoto> photos) {
        if (requestedOrder == null) return;
        Set<Long> ids = new HashSet<>(requestedOrder);
        if (requestedOrder.size() != photos.size() || ids.size() != photos.size()
                || photos.stream().anyMatch(photo -> !ids.contains(photo.getId()))) {
            throw error(ErrorCode.INVALID_REQUEST, "photoOrder는 이 여행기의 사진 ID를 빠짐없이 한 번씩 포함해야 합니다.");
        }
        for (int i = 0; i < photos.size(); i++) photos.get(i).changeOrderIndex(1000 + i);
        photoRepository.saveAllAndFlush(photos);
        for (int i = 0; i < requestedOrder.size(); i++) {
            Long id = requestedOrder.get(i);
            photos.stream().filter(photo -> photo.getId().equals(id)).findFirst().orElseThrow().changeOrderIndex(i);
        }
    }

    private String experienceTagsJson(List<String> tags) {
        if (tags.size() > 5 || new HashSet<>(tags).size() != tags.size()
                || tags.stream().anyMatch(tag -> !OnboardingQuestionBank.EXPERIENCE_TAGS.contains(tag))) {
            throw error(ErrorCode.INVALID_REQUEST, "경험 태그는 사전의 서로 다른 항목 5개 이하여야 합니다.");
        }
        try {
            return jsonMapper.writeValueAsString(tags);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("경험 태그를 저장할 수 없습니다.", exception);
        }
    }

    private List<String> parseTags(String json) {
        try {
            return jsonMapper.readValue(json, jsonMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("저장된 여행기 태그가 올바르지 않습니다.", exception);
        }
    }

    private static <E extends Enum<E>> E enumValue(String value, Class<E> type) {
        if (value == null) return null;
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw error(ErrorCode.INVALID_REQUEST, "여행기 옵션 값이 올바르지 않습니다.");
        }
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            default -> "webp";
        };
    }

    private void quietDelete(String key) {
        try {
            photoStorage.delete(key);
        } catch (RuntimeException ignored) {
            // The original failure remains primary; cleanup failures do not hide it.
        }
    }

    private static DiaryException error(ErrorCode code, String message) {
        return new DiaryException(code, message);
    }

    private static DiaryException error(ErrorCode code, String message, Map<String, Object> details) {
        return new DiaryException(code, message, details);
    }
}
