package com.yeso.backend.profile.infrastructure;

import com.yeso.backend.profile.domain.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {
}
