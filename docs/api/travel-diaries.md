# 여행기·사진 지도 API 계약

## 리소스와 권한

모든 소유자 API는 Bearer 인증이 필요하다. 친구 공개 조회는 수락된 친구만 가능하며, 링크 조회는 단일 여행기의 읽기 전용 share-session으로만 가능하다.

| Method | Path | 권한 | 핵심 계약 |
|---|---|---|---|
| POST | `/courses/{courseId}/diary` | owner | 여행기 초안 생성, 여행당 하나 |
| POST | `/diaries/{diaryId}/photos` | owner | multipart 사진 업로드, 1~30장 |
| PATCH | `/diaries/{diaryId}` | owner | 제목·본문·태그·만족도·위치 정밀도·공개 범위 수정 |
| POST | `/diaries/{diaryId}/publish` | owner | 검증 후 발행 |
| GET | `/diaries/{diaryId}` | owner/friend/link | 공개 범위별 상세 |
| GET | `/me/travel-map?from=&to=` | owner | 내가 볼 수 있는 여행기 핀 목록 |
| GET | `/friends/{userId}/travel-map?from=&to=` | accepted friend | FRIENDS 공개 핀만 |
| POST | `/diaries/{diaryId}/share-links` | owner | 읽기 전용 링크 발급 |
| POST | `/friend-requests` | user | `{recipientUserId}` 요청 |
| PATCH | `/friend-requests/{id}` | recipient | `{action:"ACCEPT|REJECT|BLOCK"}` |

`PATCH /diaries/{diaryId}` 예시:

```json
{"title":"비 오는 날의 경주","body":"...","visibility":"FRIENDS","includeInTasteProfile":true,"satisfaction":5,"experienceTags":["역사","산책"],"locationPrecision":"CITY"}
```

지도 핀 응답은 정확한 위치를 노출하지 않는 경우를 명시한다.

```json
{"diaryId":31,"title":"비 오는 날의 경주","coverPhotoUrl":"...","visitedAt":"2026-10-12","lat":35.856,"lng":129.225,"locationPrecision":"CITY","visibility":"FRIENDS"}
```

대표 오류: `DIARY_NOT_FOUND` 404, `DIARY_ALREADY_EXISTS_FOR_COURSE` 409, `DIARY_NOT_PUBLISHABLE` 422, `PHOTO_LIMIT_EXCEEDED` 400, `PHOTO_INVALID` 400, `FRIENDSHIP_REQUIRED` 403, `DIARY_SHARE_LINK_EXPIRED|REVOKED` 410.
