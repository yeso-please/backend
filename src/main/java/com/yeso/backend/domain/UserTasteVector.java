package com.yeso.backend.domain;

import com.yeso.backend.auth.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
 *
 * modelVersion뿐 아니라 templateVersion도 저장한다 — profileText를 합성하는 규칙(§3.1)이
 * 바뀌면 관광지 임베딩만 재배치해선 안 되고 이미 만들어진 사용자 벡터도 같은 텍스트 공간이
 * 아니게 되므로, 이 값으로 재계산 대상 사용자를 식별한다({@link AttractionEmbedding}과 동일한 목적).
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

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] embedding;

    @Column(nullable = false)
    private int dimension;

    /** 온보딩 응답을 합성해 만든 원본 텍스트. 재임베딩 시 재사용/디버깅용으로 함께 보관 */
    @Column(name = "profile_text", nullable = false, columnDefinition = "text")
    private String profileText;

    @Column(name = "model_version", nullable = false)
    private String modelVersion;

    @Column(name = "template_version", nullable = false)
    private int templateVersion;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public UserTasteVector(
            User user,
            byte[] embedding,
            int dimension,
            String profileText,
            String modelVersion,
            int templateVersion
    ) {
        this.user = user;
        this.userId = user.getId();
        this.embedding = embedding;
        this.dimension = dimension;
        this.profileText = profileText;
        this.modelVersion = modelVersion;
        this.templateVersion = templateVersion;
    }
}
