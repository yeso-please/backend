SET search_path TO app, public;

-- 2026-09-24 여행·공동 일정 정책(docs/mvp/decisions.md):
-- 여행은 회원만 만들고 참여한다(비회원 guest 참여 폐지). 초대·공유의 VIEW/EDIT 권한은 없어지고
-- 참여자는 모두 동등하며, 외부 공유 링크는 항상 읽기 전용이다.
-- 이 migration은 guest 데이터를 삭제한다. 개발 RDS에는 적용 전 snapshot을 남긴다.

-- 지역 결정(WORK-05)이 채울 값. 결정 전에는 NULL이다.
ALTER TABLE trip_plans
    ADD COLUMN region_selection VARCHAR(20),
    ADD COLUMN schedule_density VARCHAR(20),
    ADD CONSTRAINT ck_trip_region_selection
        CHECK (region_selection IS NULL OR region_selection IN ('RANDOM', 'CONDITIONAL', 'MANUAL')),
    ADD CONSTRAINT ck_trip_schedule_density
        CHECK (schedule_density IS NULL OR schedule_density IN ('RELAXED', 'PACKED'));

-- guest 온보딩·취향 제거. 제출을 지우면 answers·liked_trips·embedding_jobs는 FK cascade로 함께 지워진다.
DELETE FROM onboarding_submissions WHERE user_id IS NULL;
DROP TABLE guest_taste_vectors;
ALTER TABLE onboarding_submissions
    DROP CONSTRAINT ck_onboarding_owner,
    DROP COLUMN guest_participant_id,
    ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE embedding_jobs DROP CONSTRAINT ck_embedding_job_owner_type;
ALTER TABLE embedding_jobs ADD CONSTRAINT ck_embedding_job_owner_type CHECK (owner_type IN ('USER'));

-- guest 참여자와 세션 제거. 참여자는 이제 회원 한 명당 여행마다 한 줄이다.
DROP TABLE guest_sessions;
DELETE FROM trip_participants WHERE participant_type = 'GUEST' OR user_id IS NULL;
ALTER TABLE trip_participants DROP CONSTRAINT trip_participants_user_id_fkey;
ALTER TABLE trip_participants
    ALTER COLUMN user_id SET NOT NULL,
    ADD CONSTRAINT fk_trip_participants_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE trip_participants DROP CONSTRAINT ck_participant_type;
UPDATE trip_participants SET participant_type = 'MEMBER' WHERE participant_type = 'USER';
ALTER TABLE trip_participants ADD CONSTRAINT ck_participant_type CHECK (participant_type IN ('OWNER', 'MEMBER'));
-- 표시 이름은 users.nickname, 성향은 회원의 최신 제출을 쓴다. 참여 상태 전이(INVITED→READY)도 없어졌다.
ALTER TABLE trip_participants
    DROP CONSTRAINT ck_participant_status,
    DROP COLUMN status,
    DROP COLUMN display_name,
    DROP COLUMN latest_onboarding_submission_id;
ALTER TABLE trip_participants ADD CONSTRAINT uq_trip_participant_user UNIQUE (trip_plan_id, user_id);
CREATE INDEX idx_trip_participants_user ON trip_participants(user_id);

-- 초대·공유 링크의 VIEW/EDIT 권한 제거.
ALTER TABLE trip_invitations DROP CONSTRAINT ck_invite_permission, DROP COLUMN permission;
ALTER TABLE course_share_links DROP CONSTRAINT ck_share_permission, DROP COLUMN permission;
