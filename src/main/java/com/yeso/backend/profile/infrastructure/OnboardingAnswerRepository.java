package com.yeso.backend.profile.infrastructure;

import com.yeso.backend.profile.domain.OnboardingAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnboardingAnswerRepository extends JpaRepository<OnboardingAnswer, Long> {
}
