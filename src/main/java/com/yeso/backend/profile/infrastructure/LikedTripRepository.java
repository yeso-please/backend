package com.yeso.backend.profile.infrastructure;

import com.yeso.backend.profile.domain.LikedTrip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface LikedTripRepository extends JpaRepository<LikedTrip, Long> {

    /** 같은 입력이면 같은 임베딩 문장이 되도록 SIG_CD 오름차순으로 돌려준다. */
    @Query("select lt from LikedTrip lt join fetch lt.region r where lt.submission.id = :submissionId order by r.sigCd")
    List<LikedTrip> findAllBySubmissionIdOrderBySigCd(UUID submissionId);
}
