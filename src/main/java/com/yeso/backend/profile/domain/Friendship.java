package com.yeso.backend.profile.domain;

import com.yeso.backend.auth.domain.User;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 친구 관계. 방향이 있는 (user_id, friend_id) 1개 row로 표현하면 A→B, B→A가
 * 동시에 만들어져 나중에 한쪽만 수락돼도 중복 관계가 남을 수 있다.
 *
 * 그래서 두 사용자 쌍을 id 오름차순으로 정규화해 userLow/userHigh에 저장하고
 * (user_low_id, user_high_id)에 unique 제약을 건다 — 누가 먼저 요청했는지와 무관하게
 * 같은 쌍은 물리적으로 한 row만 존재할 수 있다. 실제 요청자는 requestedByUser로 별도 기록.
 *
 * 친구 초대 링크 수락은 바로 {@code ACCEPTED}로 저장한다(docs/api/profile.md 친구). 행은 동시 수락에
 * 안전하도록 {@code FriendshipRepository.insertAcceptedIfAbsent}로만 만든다.
 */
@Entity
@Table(
        name = "friendships",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_low_id", "user_high_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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
    private FriendshipStatus status;

    /** 친구가 된 시각(친구 목록의 {@code since}). */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** {@code userId} 쪽에서 본 상대. */
    public User otherThan(Long userId) {
        return userLow.getId().equals(userId) ? userHigh : userLow;
    }
}
