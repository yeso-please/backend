package com.yeso.backend.onboarding.infrastructure;

import com.yeso.backend.onboarding.domain.OnboardingSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OnboardingSubmissionRepository extends JpaRepository<OnboardingSubmission, UUID> {
}
