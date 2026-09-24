package com.yeso.backend.profile.infrastructure;

import com.yeso.backend.profile.domain.OnboardingSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OnboardingSubmissionRepository extends JpaRepository<OnboardingSubmission, UUID> {
}
