package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.TravelDiary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TravelDiaryRepository extends JpaRepository<TravelDiary, Long> {

    Optional<TravelDiary> findByTripIdAndAuthorId(Long tripId, Long authorId);

    Optional<TravelDiary> findByIdAndAuthorId(Long id, Long authorId);

    boolean existsByTripIdAndAuthorId(Long tripId, Long authorId);
}
