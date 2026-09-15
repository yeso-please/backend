package com.yeso.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
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

    @Column(name = "question_key", nullable = false)
    private String questionKey;

    @Lob
    @Column(name = "answer_value", nullable = false)
    private String answerValue;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public OnboardingResponse(User user, String questionKey, String answerValue) {
        this.user = user;
        this.questionKey = questionKey;
        this.answerValue = answerValue;
    }
}
