package com.yeso.backend.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 서비스 사용자. 이메일/비밀번호 가입과 소셜 로그인({@link SocialAccount}) 둘 다 지원한다.
 *
 * email/passwordHash 모두 nullable — 소셜 전용 계정은 둘 다 없을 수 있다
 * (데모 레포의 AppUser는 password가 NOT NULL이라 소셜 로그인 확장이 막혀 있었음, 반면교사).
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, length = 190)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(nullable = false, length = 30)
    private String nickname;

    @Column(name = "profile_image")
    private String profileImage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /** 온보딩(WORK-02) 재검사 시 이 pointer만 최신 submission으로 바꾼다 — 이전 제출은 immutable로 남는다. */
    @Column(name = "latest_onboarding_submission_id")
    private UUID latestOnboardingSubmissionId;

    public User(String email, String passwordHash, String nickname) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
    }
}
