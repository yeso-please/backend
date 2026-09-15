package com.yeso.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 사용자 취향 임베딩 벡터. users와 1:1이지만 별도 테이블로 분리해서
 * 임베딩 모델을 교체해도 users 스키마는 그대로 둘 수 있게 한다(API-DESIGN-DRAFT §2).
 *
 * embedding은 Ollama가 만든 float 배열을 직렬화한 BLOB.
 * SQLite에는 벡터 인덱스가 없으므로 검색은 애플리케이션 레벨 코사인 유사도로 처리한다(§3.2).
 */
@Entity
@Table(name = "user_taste_vectors")
@Getter
@Setter
@NoArgsConstructor
public class UserTasteVector {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @OneToOne
    @MapsId
    @jakarta.persistence.JoinColumn(name = "user_id")
    private User user;

    @Lob
    @Column(nullable = false)
    private byte[] embedding;

    /** 온보딩 응답을 합성해 만든 원본 텍스트. 재임베딩 시 재사용/디버깅용으로 함께 보관 */
    @Lob
    @Column(name = "profile_text", nullable = false)
    private String profileText;

    @Column(name = "model_version", nullable = false)
    private String modelVersion;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public UserTasteVector(User user, byte[] embedding, String profileText, String modelVersion) {
        this.user = user;
        this.userId = user.getId();
        this.embedding = embedding;
        this.profileText = profileText;
        this.modelVersion = modelVersion;
    }
}
