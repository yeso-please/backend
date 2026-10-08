package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.TravelDiary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface TravelDiaryRepository extends JpaRepository<TravelDiary, Long> {

    Optional<TravelDiary> findByTripIdAndAuthorId(Long tripId, Long authorId);

    Optional<TravelDiary> findByIdAndAuthorId(Long id, Long authorId);

    boolean existsByTripIdAndAuthorId(Long tripId, Long authorId);

    boolean existsByTripId(Long tripId);

    List<TravelDiary> findByAuthorIdOrderByVisitedFromDescIdDesc(Long authorId);

    List<TravelDiary> findByAuthorIdAndVisitedFromGreaterThanEqualAndVisitedFromLessThanEqualOrderByVisitedFromDescIdDesc(
            Long authorId, java.time.LocalDate from, java.time.LocalDate to);

    List<TravelDiary> findByAuthorIdAndStatusAndVisibilityOrderByVisitedFromDescIdDesc(
            Long authorId, com.yeso.backend.trip.domain.DiaryStatus status,
            com.yeso.backend.trip.domain.DiaryVisibility visibility);

    List<TravelDiary> findByAuthorIdAndStatusAndVisibilityAndVisitedFromGreaterThanEqualAndVisitedFromLessThanEqualOrderByVisitedFromDescIdDesc(
            Long authorId, com.yeso.backend.trip.domain.DiaryStatus status,
            com.yeso.backend.trip.domain.DiaryVisibility visibility, java.time.LocalDate from, java.time.LocalDate to);
}
