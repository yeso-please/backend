package com.yeso.backend.domain;

import com.yeso.backend.auth.domain.User;
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

import java.time.LocalDateTime;

/**
 * 온보딩 질문 1개에 대한 응답 원본. answerValue는 질문 유형에 따라
 * 단일 선택/다중 선택/자유서술이 섞이므로 구조를 고정하지 않고 JSON 문자열로 저장한다.
 * 이 원본을 합쳐 {@link UserTasteVector}의 profileText를 합성한다(API-DESIGN-DRAFT §3.1/§3.2).
 *
 * submissionId로 "한 번에 제출한 응답 묶음"의 경계를 표시한다. 이게 없으면 재응답(취향
 * 재검사) 때 일부 질문을 건너뛴 걸 "미답변(중립)"인지 "이전 제출의 답을 그대로 쓴 것"인지
 * 구분할 수 없다 — FEATURE-SPEC §2가 요구하는 "최신 제출만 현재 벡터에 반영, 그 안에서
 * 빠진 질문은 중립 처리"를 지키려면 같은 submissionId 묶음 단위로만 최신 응답을 골라야 한다.
 */
@Entity
@Table(name = "onboarding_responses")
@Getter
@Setter
@NoArgsConstructor
public class OnboardingResponse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 같은 제출 요청에서 생성된 응답들은 모두 같은 값(UUID)을 가진다 */
    @Column(name = "submission_id", nullable = false, length = 36)
    private String submissionId;

    @Column(name = "question_key", nullable = false)
    private String questionKey;

    @Column(name = "answer_value", nullable = false, columnDefinition = "text")
    private String answerValue;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public OnboardingResponse(User user, String submissionId, String questionKey, String answerValue) {
        this.user = user;
        this.submissionId = submissionId;
        this.questionKey = questionKey;
        this.answerValue = answerValue;
    }
}
