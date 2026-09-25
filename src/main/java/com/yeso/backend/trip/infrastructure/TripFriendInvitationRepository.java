package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.FriendInvitationStatus;
import com.yeso.backend.trip.domain.TripFriendInvitation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TripFriendInvitationRepository extends JpaRepository<TripFriendInvitation, Long> {

    Optional<TripFriendInvitation> findByTripPlanIdAndInviteeIdAndStatus(
            Long tripPlanId, Long inviteeId, FriendInvitationStatus status);

    /** 수락·거절·취소를 초대 단위로 직렬화한다. 같은 초대를 두 요청이 함께 처리하지 않는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from TripFriendInvitation i where i.id = :id")
    Optional<TripFriendInvitation> lockById(@Param("id") Long id);

    /** 받은 초대 목록(4-7). 여행·지역·보낸 사람을 한 번에 읽는다. */
    @Query("""
            select i from TripFriendInvitation i
            join fetch i.tripPlan t left join fetch t.region join fetch i.inviter
            where i.invitee.id = :inviteeId and i.status = :status
            order by i.createdAt desc, i.id desc
            """)
    List<TripFriendInvitation> findReceived(
            @Param("inviteeId") Long inviteeId, @Param("status") FriendInvitationStatus status);

    /** 이 여행에서 보낸 친구 초대 전체(4-14). */
    @Query("""
            select i from TripFriendInvitation i join fetch i.inviter join fetch i.invitee
            where i.tripPlan.id = :tripPlanId
            order by i.createdAt desc, i.id desc
            """)
    List<TripFriendInvitation> findSent(@Param("tripPlanId") Long tripPlanId);

    /** 초대 링크(4-5)로 먼저 참여하면 대기 중인 친구 초대를 수락 처리한다(docs/api/trip.md 4-6). */
    @Modifying(flushAutomatically = true)
    @Query("""
            update TripFriendInvitation i set i.status = com.yeso.backend.trip.domain.FriendInvitationStatus.ACCEPTED,
                i.handledAt = :now
            where i.tripPlan.id = :tripPlanId and i.invitee.id = :inviteeId
                and i.status = com.yeso.backend.trip.domain.FriendInvitationStatus.PENDING
            """)
    int acceptPending(@Param("tripPlanId") Long tripPlanId, @Param("inviteeId") Long inviteeId, @Param("now") LocalDateTime now);
}
