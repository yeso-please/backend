package com.yeso.backend.domain;

import com.yeso.backend.auth.domain.User;
import com.yeso.backend.domain.enums.FriendshipStatus;
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
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 친구 관계. 방향이 있는 (user_id, friend_id) 1개 row로 표현하면 A→B, B→A가
 * 동시에 만들어져 나중에 한쪽만 수락돼도 중복 관계가 남을 수 있다.
 *
 * 그래서 두 사용자 쌍을 id 오름차순으로 정규화해 userLow/userHigh에 저장하고
 * (user_low_id, user_high_id)에 unique 제약을 건다 — 누가 먼저 요청했는지와 무관하게
 * 같은 쌍은 물리적으로 한 row만 존재할 수 있다. 실제 요청자는 requestedByUser로 별도 기록.
 */
@Entity
@Table(
        name = "friendships",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_low_id", "user_high_id"})
)
@jakarta.persistence.EntityListeners(org.springframework.data.jpa.domain.support.AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class Friendship {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_low_id", nullable = false)
    private User userLow;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_high_id", nullable = false)
    private User userHigh;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_user_id", nullable = false)
    private User requestedByUser;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FriendshipStatus status = FriendshipStatus.PENDING;

    @org.springframework.data.annotation.CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** a, b는 순서 무관 — 생성자가 id 기준으로 정규화해 userLow/userHigh에 배치한다 */
    public Friendship(User a, User b, User requestedByUser) {
        if (a.getId() < b.getId()) {
            this.userLow = a;
            this.userHigh = b;
        } else {
            this.userLow = b;
            this.userHigh = a;
        }
        this.requestedByUser = requestedByUser;
    }
}
