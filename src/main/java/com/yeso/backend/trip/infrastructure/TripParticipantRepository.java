package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.TripParticipant;
import com.yeso.backend.trip.domain.TripPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TripParticipantRepository extends JpaRepository<TripParticipant, Long> {

    List<TripParticipant> findByTripPlanIdOrderByCreatedAtAsc(Long tripPlanId);

    Optional<TripParticipant> findByTripPlanIdAndUserId(Long tripPlanId, Long userId);

    boolean existsByTripPlanIdAndUserId(Long tripPlanId, Long userId);

    long countByTripPlanId(Long tripPlanId);

    /** 사용자가 참여 중인(만들었거나 수락한) 여행 중 [start, end]와 하루라도 겹치는 여행. */
    @Query("""
            select p.tripPlan from TripParticipant p
            where p.user.id = :userId and p.tripPlan.startDate <= :end and p.tripPlan.endDate >= :start
            order by p.tripPlan.startDate
            """)
    List<TripPlan> findOverlappingTrips(
            @Param("userId") Long userId, @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("""
            select p.tripPlan from TripParticipant p
            where p.user.id = :userId
            order by p.tripPlan.startDate, p.tripPlan.id
            """)
    List<TripPlan> findTripsOf(@Param("userId") Long userId);
}
