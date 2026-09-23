package com.yeso.backend.onboarding.infrastructure;

import com.yeso.backend.onboarding.domain.OnboardingAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnboardingAnswerRepository extends JpaRepository<OnboardingAnswer, Long> {
}
