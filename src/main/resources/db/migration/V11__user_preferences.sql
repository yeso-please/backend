-- 회원 설정(docs/api/profile.md 2-11·2-12). 코스를 만들 때 취향을 반영할지의 기본값이다(2026-09-27 팀 회의).
-- 설정을 바꾼 적 없는 회원은 행이 없고 기본값 TASTE로 본다.
CREATE TABLE user_preferences (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    course_taste_mode VARCHAR(20) NOT NULL DEFAULT 'TASTE',
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_user_preference_course_taste_mode CHECK (course_taste_mode IN ('TASTE', 'RANDOM'))
);

-- 사용자가 완전 랜덤을 고른 코스는 추천 모드 RANDOM으로 기록한다.
ALTER TABLE trip_plans DROP CONSTRAINT ck_trip_recommendation_mode;
ALTER TABLE trip_plans ADD CONSTRAINT ck_trip_recommendation_mode
    CHECK (recommendation_mode IS NULL OR recommendation_mode IN ('PERSONALIZED', 'TOUR_OFFICIAL', 'RULE_BASED', 'RANDOM'));
