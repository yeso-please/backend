package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.DiaryTasteSignal;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiaryTasteSignalRepository extends JpaRepository<DiaryTasteSignal, Long> {
}
