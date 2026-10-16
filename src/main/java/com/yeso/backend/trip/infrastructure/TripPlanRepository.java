package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.auth.domain.User;
import com.yeso.backend.trip.domain.CourseTitleSource;
import com.yeso.backend.trip.domain.TripPlan;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TripPlanRepository extends JpaRepository<TripPlan, Long> {

    /**
     * 같은 사용자의 여행 생성·초대 수락을 직렬화한다. 사용자 행을 잠가, 두 요청이 동시에 "겹치는 여행 없음"을
     * 보고 각자 저장하는 경쟁을 막는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :userId")
    Optional<User> lockUser(@Param("userId") Long userId);

    /** 탈퇴·수락을 여행 단위로 직렬화한다. 마지막 두 참여자가 동시에 나가도 여행이 고아로 남지 않는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TripPlan t where t.id = :tripId")
    Optional<TripPlan> lockById(@Param("tripId") Long tripId);

    /**
     * 코스를 만든 직후 ai 문장(제목·소개)을 붙인다(5-1, D16). 여행 버전을 올리지 않는다 — 생성 응답의 {@code version}을
     * 받은 화면이 그대로 편집할 수 있어야 하기 때문이다. 그사이 다른 변경이 있었으면(버전이 다르면) 0행이다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update TripPlan t set t.title = :title, t.titleSource = :titleSource,
                t.courseIntro = :intro, t.courseIntroSource = :introSource
            where t.id = :tripId and t.version = :version""")
    int applyCourseText(@Param("tripId") Long tripId, @Param("version") int version, @Param("title") String title,
                        @Param("titleSource") CourseTitleSource titleSource, @Param("intro") String intro,
                        @Param("introSource") String introSource);
}
