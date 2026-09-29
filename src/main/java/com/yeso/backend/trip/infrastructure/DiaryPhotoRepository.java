package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.DiaryPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DiaryPhotoRepository extends JpaRepository<DiaryPhoto, Long> {

    List<DiaryPhoto> findByDiaryIdOrderByOrderIndexAscIdAsc(Long diaryId);

    Optional<DiaryPhoto> findByIdAndDiaryId(Long id, Long diaryId);

    int countByDiaryId(Long diaryId);
}
