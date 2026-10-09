package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.DiaryTasteSignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DiaryTasteSignalRepository extends JpaRepository<DiaryTasteSignal, Long> {

    interface RecentSignal {
        Short getSatisfaction();

        String getExperienceTags();

        LocalDateTime getUpdatedAt();
    }

    /** 작성자의 여행기 취향 신호 최신순(발행·설정을 끄거나 삭제하면 행이 사라진다). */
    @Query(value = """
            select s.satisfaction as satisfaction, s.experience_tags as experienceTags, s.updated_at as updatedAt
            from {h-schema}diary_taste_signals s
            where s.author_user_id = :userId
            order by s.updated_at desc, s.diary_id desc
            limit :limit
            """, nativeQuery = true)
    List<RecentSignal> findRecentByAuthor(@Param("userId") Long userId, @Param("limit") int limit);
}
