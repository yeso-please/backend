package com.yeso.backend.domain;

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
 * 친구 요청/수락. user가 요청자, friend가 대상 — 방향이 있는 1개 row로 관계를 표현하고,
 * 상태(PENDING/ACCEPTED)로 진행 단계를 추적한다.
 */
@Entity
@Table(
        name = "friendships",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "friend_id"})
)
@Getter
@Setter
@NoArgsConstructor
public class Friendship {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "friend_id", nullable = false)
    private User friend;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FriendshipStatus status = FriendshipStatus.PENDING;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Friendship(User user, User friend) {
        this.user = user;
        this.friend = friend;
    }
}
