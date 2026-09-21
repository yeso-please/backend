package com.yeso.backend.onboarding.infrastructure;

import com.yeso.backend.domain.UserTasteVector;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTasteVectorRepository extends JpaRepository<UserTasteVector, Long> {
}
