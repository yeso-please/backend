package com.yeso.backend.domain;

import com.yeso.backend.auth.domain.User;
import com.yeso.backend.domain.enums.InteractionAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 사용자의 관광지 반응 기록(좋아요/저장). 이 로그를 모아
 * {@link UserTasteVector}를 만족도 가중 평균으로 갱신한다
 * (데모 TravelerProfileService의 "여행 DNA" 갱신 방식과 동일한 아이디어).
 */
@Entity
@Table(name = "user_interactions")
@jakarta.persistence.EntityListeners(org.springframework.data.jpa.domain.support.AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class UserInteraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attraction_id", nullable = false)
    private Attraction attraction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InteractionAction action;

    @org.springframework.data.annotation.CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public UserInteraction(User user, Attraction attraction, InteractionAction action) {
        this.user = user;
        this.attraction = attraction;
        this.action = action;
    }
}
