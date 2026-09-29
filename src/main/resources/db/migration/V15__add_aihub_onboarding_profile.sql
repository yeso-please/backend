-- AI Hub 여행로그 설문을 새 온보딩 기본 형식으로 추가한다.
-- 기존 제출은 보존하고 새 구조화 응답은 템플릿 v2 입력으로 저장한다.
ALTER TABLE onboarding_submissions
    ADD COLUMN travel_styles JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN travel_motives JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT ck_onboarding_travel_styles_shape CHECK (
        question_version <> 'aihub-traveler-v1'
        OR (
            jsonb_typeof(travel_styles) = 'object'
            AND travel_styles ?& ARRAY['1', '3', '5', '6']
            AND travel_styles - ARRAY['1', '3', '5', '6'] = '{}'::jsonb
            AND travel_styles->>'1' ~ '^[1-7]$'
            AND travel_styles->>'3' ~ '^[1-7]$'
            AND travel_styles->>'5' ~ '^[1-7]$'
            AND travel_styles->>'6' ~ '^[1-7]$'
        )
    ),
    ADD CONSTRAINT ck_onboarding_travel_motives_shape CHECK (
        question_version <> 'aihub-traveler-v1'
        OR (
            jsonb_typeof(travel_motives) = 'array'
            AND jsonb_array_length(travel_motives) <= 3
        )
    );
