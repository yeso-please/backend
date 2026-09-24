package com.yeso.backend.onboarding.infrastructure;

import com.yeso.backend.profile.domain.GuestTasteVector;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuestTasteVectorRepository extends JpaRepository<GuestTasteVector, Long> {
}
