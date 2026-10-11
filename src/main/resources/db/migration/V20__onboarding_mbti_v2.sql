SET search_path TO app, public;

-- 온보딩 v2(aihub-traveler-v2): AI Hub 항목 + 여행 MBTI 12문항. MBTI는 표시용이라 임베딩에 쓰지 않는다.
-- MBTI 답은 문항 번호가 여행 스타일 1·3·5·6과 겹쳐 onboarding_answers에 넣지 않고 JSONB로 둔다. v1·구형 행은 NULL.
ALTER TABLE onboarding_submissions
    ADD COLUMN mbti_answers JSONB;

-- V15의 모양 제약은 aihub-traveler-v1에만 걸려 있었다. v2에도 같은 모양을 요구한다.
ALTER TABLE onboarding_submissions
    DROP CONSTRAINT ck_onboarding_travel_styles_shape,
    DROP CONSTRAINT ck_onboarding_travel_motives_shape;

ALTER TABLE onboarding_submissions
    ADD CONSTRAINT ck_onboarding_travel_styles_shape CHECK (
        question_version NOT IN ('aihub-traveler-v1', 'aihub-traveler-v2')
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
        question_version NOT IN ('aihub-traveler-v1', 'aihub-traveler-v2')
        OR (
            jsonb_typeof(travel_motives) = 'array'
            AND jsonb_array_length(travel_motives) <= 3
        )
    ),
    ADD CONSTRAINT ck_onboarding_mbti_v2_shape CHECK (
        question_version <> 'aihub-traveler-v2'
        OR (
            mbti_code ~ '^[EI][SN][TF][JP]$'
            AND jsonb_typeof(mbti_answers) = 'object'
            AND mbti_answers ?& ARRAY['1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12']
            AND mbti_answers - ARRAY['1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12'] = '{}'::jsonb
            AND NOT jsonb_path_exists(mbti_answers, '$.* ? (@ != 1 && @ != 2)')
        )
    );
