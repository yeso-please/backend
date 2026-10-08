package com.yeso.backend.trip.application.diary;

import com.yeso.backend.attraction.domain.Region;
import com.yeso.backend.attraction.infrastructure.RegionRepository;
import com.yeso.backend.auth.domain.UserNotFoundException;
import com.yeso.backend.auth.infrastructure.UserRepository;
import com.yeso.backend.profile.application.friend.FriendQueryService;
import com.yeso.backend.shared.exception.ErrorCode;
import com.yeso.backend.shared.token.OpaqueTokenGenerator;
import com.yeso.backend.shared.token.TokenAudience;
import com.yeso.backend.trip.domain.*;
import com.yeso.backend.trip.infrastructure.*;
import com.yeso.backend.trip.presentation.diary.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional
public class DiaryQueryService {
    private static final int DEFAULT_DAYS = 7;
    private static final int SESSION_HOURS = 2;

    private final TravelDiaryRepository diaries;
    private final DiaryPhotoRepository photos;
    private final DiaryShareLinkRepository links;
    private final DiaryShareSessionRepository sessions;
    private final FriendQueryService friendQueryService;
    private final UserRepository users;
    private final RegionRepository regions;
    private final DiaryService diaryService;
    private final OpaqueTokenGenerator tokens;
    private final Clock clock;

    @Transactional(readOnly = true)
    public DiaryResponse get(Long viewerId, Long diaryId) {
        TravelDiary diary = diaries.findById(diaryId).orElseThrow(this::notFound);
        boolean owner = diary.getAuthor().getId().equals(viewerId);
        if (!owner) {
            if (diary.getStatus() != DiaryStatus.PUBLISHED || diary.getVisibility() != DiaryVisibility.FRIENDS
                    || !isFriend(viewerId, diary.getAuthor().getId())) throw notFound();
        }
        return diaryService.responseAtPrecision(viewerId, diary, owner,
                owner ? DiaryLocationPrecision.EXACT : diary.getLocationPrecision(), null, null);
    }

    @Transactional(readOnly = true)
    public List<DiaryMapPinResponse> myMap(Long userId, LocalDate from, LocalDate to) {
        validateRange(from, to);
        List<TravelDiary> records = from == null ? diaries.findByAuthorIdOrderByVisitedFromDescIdDesc(userId)
                : diaries.findByAuthorIdAndVisitedFromGreaterThanEqualAndVisitedFromLessThanEqualOrderByVisitedFromDescIdDesc(userId, from, to);
        return records.stream().map(diary -> pin(diary, true)).toList();
    }

    @Transactional(readOnly = true)
    public List<DiaryMapPinResponse> friendMap(Long viewerId, Long authorId, LocalDate from, LocalDate to) {
        validateRange(from, to);
        if (!isFriend(viewerId, authorId)) throw new DiaryException(ErrorCode.FRIEND_REQUIRED, "수락된 친구만 지도를 볼 수 있습니다.");
        List<TravelDiary> records = from == null
                ? diaries.findByAuthorIdAndStatusAndVisibilityOrderByVisitedFromDescIdDesc(authorId, DiaryStatus.PUBLISHED, DiaryVisibility.FRIENDS)
                : diaries.findByAuthorIdAndStatusAndVisibilityAndVisitedFromGreaterThanEqualAndVisitedFromLessThanEqualOrderByVisitedFromDescIdDesc(
                    authorId, DiaryStatus.PUBLISHED, DiaryVisibility.FRIENDS, from, to);
        return records.stream().map(diary -> pin(diary, false)).toList();
    }

    public DiaryShareLinkResponse createLink(Long userId, Long diaryId, DiaryShareLinkRequest request) {
        TravelDiary diary = diaryService.requireOwnerDiary(userId, diaryId);
        requireLinkReady(diary);
        int days = request == null || request.expiresInDays() == null ? DEFAULT_DAYS : request.expiresInDays();
        if (days < 1 || days > 30) throw new DiaryException(ErrorCode.INVALID_EXPIRES_IN_DAYS, "공유 기간은 1~30일이어야 합니다.");
        String token = tokens.generate(TokenAudience.DIARY_SHARE_LINK);
        DiaryShareLink link = links.saveAndFlush(new DiaryShareLink(diary, tokens.hash(token),
                LocalDateTime.now(clock).plusDays(days), users.findById(userId).orElseThrow(() -> new UserNotFoundException(userId))));
        return DiaryShareLinkResponse.created(link, token);
    }

    @Transactional(readOnly = true)
    public List<DiaryShareLinkResponse> listLinks(Long userId, Long diaryId) {
        diaryService.requireOwnerDiary(userId, diaryId);
        return links.findByDiaryIdOrderByCreatedAtDesc(diaryId).stream().map(DiaryShareLinkResponse::summary).toList();
    }

    public void revokeLink(Long userId, Long diaryId, Long linkId) {
        diaryService.requireOwnerDiary(userId, diaryId);
        DiaryShareLink link = links.findByIdAndDiaryId(linkId, diaryId).orElseThrow(this::linkNotFound);
        link.revoke(LocalDateTime.now(clock));
    }

    public void deleteLinks(Long diaryId) {
        links.deleteAll(links.findByDiaryIdOrderByCreatedAtDesc(diaryId));
    }

    public String open(String token) {
        tokens.requireAudience(token, TokenAudience.DIARY_SHARE_LINK);
        DiaryShareLink link = links.findByTokenHash(tokens.hash(token)).orElseThrow(this::linkNotFound);
        checkLink(link);
        String raw = tokens.generate(TokenAudience.DIARY_SHARE_SESSION);
        sessions.save(new DiaryShareSession(link, tokens.hash(raw), LocalDateTime.now(clock).plusHours(SESSION_HOURS)));
        return raw;
    }

    @Transactional(readOnly = true)
    public DiaryResponse viewShared(String sessionToken) {
        if (!TokenAudience.DIARY_SHARE_SESSION.matches(sessionToken)) {
            if (sessionToken != null && !sessionToken.isBlank()) tokens.requireAudience(sessionToken, TokenAudience.DIARY_SHARE_SESSION);
            throw new DiaryException(ErrorCode.SHARE_SESSION_INVALID, "공유 세션이 없거나 만료되었습니다.");
        }
        DiaryShareSession session = sessions.findBySessionTokenHash(tokens.hash(sessionToken))
                .orElseThrow(() -> new DiaryException(ErrorCode.SHARE_SESSION_INVALID, "공유 세션이 없거나 만료되었습니다."));
        if (!session.getExpiresAt().isAfter(LocalDateTime.now(clock)))
            throw new DiaryException(ErrorCode.SHARE_SESSION_INVALID, "공유 세션이 없거나 만료되었습니다.");
        DiaryShareLink link = session.getShareLink();
        checkLink(link);
        requireLinkReady(link.getDiary());
        TravelDiary diary = link.getDiary();
        Region region = diary.getRegionSigCd() == null ? null : regions.findById(diary.getRegionSigCd()).orElse(null);
        Double fallbackLat = diary.getLocationPrecision() == DiaryLocationPrecision.CITY && region != null ? region.getLat() : null;
        Double fallbackLng = diary.getLocationPrecision() == DiaryLocationPrecision.CITY && region != null ? region.getLng() : null;
        return diaryService.responseAtPrecision(null, diary, false, diary.getLocationPrecision(), fallbackLat, fallbackLng);
    }

    private DiaryMapPinResponse pin(TravelDiary diary, boolean owner) {
        DiaryLocationPrecision precision = diary.getLocationPrecision();
        Region region = diary.getRegionSigCd() == null ? null : regions.findById(diary.getRegionSigCd()).orElse(null);
        Double lat = null, lng = null;
        if (owner || precision == DiaryLocationPrecision.EXACT) {
            DiaryPhoto cover = photos.findByDiaryIdOrderByOrderIndexAscIdAsc(diary.getId()).stream()
                    .filter(photo -> photo.getId().equals(diary.getCoverPhotoId())).findFirst().orElse(null);
            lat = cover == null ? null : cover.getLatitude();
            lng = cover == null ? null : cover.getLongitude();
        }
        if ((lat == null || lng == null) && region != null
                && (owner || precision == DiaryLocationPrecision.CITY || precision == DiaryLocationPrecision.HIDDEN)) {
            lat = region.getLat(); lng = region.getLng();
        }
        var cover = diaryService.coverResponse(diary);
        return new DiaryMapPinResponse(diary.getId(), diary.getTitle(), diary.getCourseTitle(),
                cover == null ? null : cover.thumbnailUrl(), diary.getVisitedFrom(),
                region == null ? null : region.getSigCd(),
                region == null ? null : region.getProvince() + " " + region.getCity(), lat, lng, precision,
                diary.getVisibility(), diary.getStatus());
    }

    private boolean isFriend(Long a, Long b) {
        if (a == null || b == null || a.equals(b)) return false;
        return friendQueryService.areFriends(a, b);
    }

    private void requireLinkReady(TravelDiary diary) {
        if (diary.getStatus() != DiaryStatus.PUBLISHED || diary.getVisibility() != DiaryVisibility.LINK)
            throw new DiaryException(ErrorCode.DIARY_NOT_PUBLISHABLE, "발행된 LINK 공개 여행기만 공유할 수 있습니다.",
                    Map.of("missing", List.of(diary.getStatus() != DiaryStatus.PUBLISHED ? "PUBLISHED" : "VISIBILITY_LINK")));
    }

    private void checkLink(DiaryShareLink link) {
        if (link.revoked()) throw new DiaryException(ErrorCode.DIARY_SHARE_LINK_REVOKED, "공유 링크가 폐기되었습니다.");
        if (!link.activeAt(LocalDateTime.now(clock))) throw new DiaryException(ErrorCode.DIARY_SHARE_LINK_EXPIRED, "공유 링크가 만료되었습니다.");
        if (link.getDiary().getStatus() != DiaryStatus.PUBLISHED || link.getDiary().getVisibility() != DiaryVisibility.LINK)
            throw new DiaryException(ErrorCode.DIARY_SHARE_LINK_REVOKED, "여행기 공개 범위가 바뀌어 공유 링크가 더 이상 유효하지 않습니다.");
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if ((from == null) != (to == null) || (from != null && to.isBefore(from)))
            throw new DiaryException(ErrorCode.INVALID_REQUEST, "from과 to를 함께 지정하고 from은 to보다 늦을 수 없습니다.");
    }

    private DiaryException notFound() { return new DiaryException(ErrorCode.DIARY_NOT_FOUND, "여행기를 찾을 수 없습니다."); }
    private DiaryException linkNotFound() { return new DiaryException(ErrorCode.DIARY_SHARE_LINK_NOT_FOUND, "여행기 공유 링크를 찾을 수 없습니다."); }
}
