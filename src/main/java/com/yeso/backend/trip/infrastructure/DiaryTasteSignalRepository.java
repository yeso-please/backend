package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.DiaryTasteSignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DiaryTasteSignalRepository extends JpaRepository<DiaryTasteSignal, Long> {

    interface FeedbackRow {
        Long getDiaryId();

        Short getSatisfaction();

        Long getAttractionId();
    }

    /**
     * 작성자의 취향 신호(발행됐고 취향 반영을 켠 여행기)와 그 여행의 최종 코스 관광지. 최근 발행 순.
     * 신호 행은 발행·수정 때 동기화되므로(DiaryService) 여기 있는 것만 반영 대상이다.
     */
    @Query(value = """
            select s.diary_id as diaryId, s.satisfaction as satisfaction, ci.attraction_id as attractionId
            from {h-schema}diary_taste_signals s
            join {h-schema}travel_diaries d on d.id = s.diary_id
            join {h-schema}course_items ci on ci.trip_plan_id = d.trip_id and ci.kind = 'ATTRACTION'
            where s.author_user_id = :userId and ci.attraction_id is not null
            order by d.published_at desc nulls last, s.diary_id desc, ci.day_index, ci.order_index
            """, nativeQuery = true)
    List<FeedbackRow> findFeedbackRows(@Param("userId") Long userId);
}
