package com.yeso.backend.profile.infrastructure;

import com.yeso.backend.profile.domain.UserTasteVector;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTasteVectorRepository extends JpaRepository<UserTasteVector, Long> {
}
