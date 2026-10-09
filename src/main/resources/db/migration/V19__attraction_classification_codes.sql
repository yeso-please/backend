SET search_path TO app, public;

-- TourAPI 새 분류체계(lclsSystm1~3) 코드. 콘텐츠 유형(content_type_id)만으로는 캠핑장을 구분할 수 없다
-- (레포츠 28의 약 59%가 분류상 숙박 > 캠핑 AC05). 값은 수집 배치가 채우며, 비어 있으면 기존 판정을 그대로 쓴다.
ALTER TABLE attractions
    ADD COLUMN lcls_systm1 VARCHAR(10),
    ADD COLUMN lcls_systm2 VARCHAR(10),
    ADD COLUMN lcls_systm3 VARCHAR(20);
