package com.yeso.backend.profile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/** 문항 1개에 대한 답(1 또는 2). 제출과 함께 immutable이다. */
@Entity
@Table(name = "onboarding_answers")
@Getter
@Setter
@NoArgsConstructor
public class OnboardingAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id", nullable = false)
    private OnboardingSubmission submission;

    @Column(name = "question_key", nullable = false)
    private String questionKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answer_value", nullable = false, columnDefinition = "jsonb")
    private Integer choice;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public OnboardingAnswer(OnboardingSubmission submission, int questionNumber, int choice) {
        this.submission = submission;
        this.questionKey = String.valueOf(questionNumber);
        this.choice = choice;
    }
}
