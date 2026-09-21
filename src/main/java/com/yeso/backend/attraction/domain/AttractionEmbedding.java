package com.yeso.backend.attraction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 관광지 임베딩. 사전 배치로만 채워진다(추천 요청 시점에 즉석 계산하지 않음, §3).
 *
 * modelVersion/templateVersion을 함께 저장해서 임베딩 모델이나
 * 텍스트 합성 규칙(§3.1)이 바뀌면 어떤 행이 재생성 대상인지 판별할 수 있게 한다.
 */
@Entity
@Table(name = "attraction_embeddings")
@jakarta.persistence.EntityListeners(org.springframework.data.jpa.domain.support.AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class AttractionEmbedding {

    @Id
    @Column(name = "attraction_id")
    private Long attractionId;

    @OneToOne
    @MapsId
    @JoinColumn(name = "attraction_id")
    private Attraction attraction;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] embedding;

    @Column(nullable = false)
    private int dimension;

    @Column(name = "model_version", nullable = false)
    private String modelVersion;

    @Column(name = "template_version", nullable = false)
    private int templateVersion;

    @org.springframework.data.annotation.LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public AttractionEmbedding(
            Attraction attraction,
            byte[] embedding,
            int dimension,
            String modelVersion,
            int templateVersion
    ) {
        this.attraction = attraction;
        this.attractionId = attraction.getId();
        this.embedding = embedding;
        this.dimension = dimension;
        this.modelVersion = modelVersion;
        this.templateVersion = templateVersion;
    }
}
