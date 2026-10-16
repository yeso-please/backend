# 코스 변경 기록 설계 (D11)

2026-10-16 디자인 확정(E1 "함께 만드는 코스", E2 "친구 변경 표시")을 위한 저장·계산 설계다. 계약은 [trip.md 5장 변경 기록](../api/trip.md#변경-기록), 정책은 [product.md 결정 기록](../product.md#결정-기록).

## 개념

- **기준점**: 코스를 생성·재생성한 시점의 코스(`trip_plans.course_baseline_at`). 그 뒤의 추가·교체·삭제가 "변경"이다.
- 재생성(5-1)과 코스 비우기(3-7 `replaceCourse`)는 기록을 모두 지운다(D11-3). 이전 기준점과 비교하지 않는다.
- 이력 로그(모든 편집을 순서대로)는 두지 않는다. 화면에 필요한 것은 **현재 코스의 항목별 출처**, **지워진 장소 목록**, **일자별 수**, **최근 변경 한 건**뿐이다.

## 저장 (V22)

| 위치 | 칼럼 | 뜻 |
|---|---|---|
| `course_items` | `origin` | `BASE` \| `ADDED` \| `REPLACED` (관광지만, 식사는 null) |
| | `changed_by_user_id`, `changed_by_nickname` | 마지막으로 추가·교체·되살린 사람. 닉네임은 스냅샷이라 탈퇴·내보내기 뒤에도 남는다 |
| | `replaced_from_attraction_id`, `replaced_from_name` | 기준 장소를 **처음** 교체할 때의 원래 장소. 다시 교체해도 바꾸지 않는다(항상 "기준 장소 → 지금 장소") |
| | `change_note`, `changed_at` | 교체 사유(선택, 100자)와 시각 |
| `course_removals` | 행 하나 = 지운 관광지 하나 | 이름 스냅샷, 지운 날(`day_index`), 지울 때의 `origin`, 지운 사람·닉네임, 사유, 시각, 되살린 시각(`restored_at`) |

## 상태 전이 (관광지 항목)

| 지금 | 편집 | 결과 |
|---|---|---|
| (생성) | 5-1 | `BASE` |
| `BASE` | REPLACE | `REPLACED`, `replacedFrom` = 원래 장소 |
| `REPLACED` | REPLACE | `REPLACED`, `replacedFrom` 유지 |
| `ADDED` | REPLACE | `ADDED`(추가한 자리를 바꾼 것) |
| (없음) | ADD | `ADDED` |
| 무엇이든 | REMOVE | 항목 삭제 + `course_removals`에 그때의 `origin`으로 기록(Q6: `ADDED`도 기록) |
| 제외 기록 | RESTORE | 기록의 `origin`이 `BASE`면 `BASE`, 아니면 `ADDED`. 기록에 `restored_at` |

`REPLACED` 항목을 지운 뒤 되살리면 그 장소(교체한 장소)가 `ADDED`로 돌아온다. 원래 기준 장소는 따로 되살리지 않는다(교체할 때 제외 기록을 남기지 않기 때문이다).

## 계산 (응답할 때)

- `changes.days[d]`: 그날 `origin = ADDED`/`REPLACED`인 항목 수, 그날(`day_index`) 지워지고 아직 되살리지 않은 기록 수.
- `changes.latest`: `changed_at`이 가장 늦은 항목(같으면 나중에 생긴 행)과 가장 최근 제외 기록 중 늦은 것. 되살린 `BASE` 항목은 `RESTORED`.
- 공유 링크 화면(4-13)은 사람을 지우고, 초대 둘러보기(4-16)는 보여 준다(D8).

## 동시성

모든 편집은 기존과 같이 여행 행 잠금 + 버전 확인(`TRIP_VERSION_CONFLICT`) 안에서 한다. 변경 기록은 같은 트랜잭션에서 쓰므로 편집과 어긋나지 않는다.

## 기존 데이터

V22가 기존 관광지 항목을 `source = MANUAL`이면 `ADDED`, 아니면 `BASE`로 채우고, 기준점은 `course_first_generated_at`으로 둔다. 기존 수동 항목은 누가 넣었는지 모른다(`by: null`).
