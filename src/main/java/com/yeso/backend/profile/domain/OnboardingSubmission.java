package com.yeso.backend.profile.domain;

import com.yeso.backend.auth.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
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
import java.util.List;
import java.util.UUID;

/**
 * 온보딩 제출은 immutable이다 — 재검사는 새 row를 만들고
 * {@link User#getLatestOnboardingSubmissionId()}(또는 guest participant의 pointer)만 바꾼다.
 * user 또는 guest participant 중 정확히 하나에 귀속하며(DB CHECK), 이번 범위는 user 소유만 다룬다
 * (guest는 WORK-04 invite 세션이 있어야 가능).
 */
@Entity
@Table(name = "onboarding_submissions")
@Getter
@Setter
@NoArgsConstructor
public class OnboardingSubmission {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "guest_participant_id")
    private Long guestParticipantId;

    @Column(name = "question_version", nullable = false, length = 50)
    private String questionVersion;

    @Column(name = "mbti_code", length = 20)
    private String mbtiCode;

    @Column(name = "profile_text", columnDefinition = "text")
    private String profileText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubmissionStatus status = SubmissionStatus.SUBMITTED;

    @Enumerated(EnumType.STRING)
    @Column(name = "taste_status", nullable = false, length = 20)
    private TasteStatus tasteStatus = TasteStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_density", nullable = false, length = 20)
    private ScheduleDensity scheduleDensity;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "experience_tags", nullable = false, columnDefinition = "jsonb")
    private List<String> experienceTags = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "exclude_tags", nullable = false, columnDefinition = "jsonb")
    private List<String> excludeTags = List.of();

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public OnboardingSubmission(
            User user,
            String questionVersion,
            String mbtiCode,
            String profileText,
            ScheduleDensity scheduleDensity,
            List<String> experienceTags,
            List<String> excludeTags
    ) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.questionVersion = questionVersion;
        this.mbtiCode = mbtiCode;
        this.profileText = profileText;
        this.scheduleDensity = scheduleDensity;
        this.experienceTags = experienceTags;
        this.excludeTags = excludeTags;
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.completedAt = now;
    }
}
