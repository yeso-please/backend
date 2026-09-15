package com.yeso.backend.domain;

import com.yeso.backend.domain.enums.EmbeddingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.Arrays;
import java.util.List;

/**
 * 관광지. description은 TourAPI 원천 특성상 상당수 비어 있다
 * (demo 레포 실측: 전체의 15.5%만 보유 — DEMO-REFERENCE.md 참고).
 * 임베딩 텍스트 합성(§3.1)은 이 공백을 항상 전제로 폴백을 둔다.
 *
 * embeddingStatus는 신규 등록·description 갱신 시 PENDING으로 리셋되고,
 * 배치가 attraction_embeddings를 채우면 DONE으로 바뀐다(§3.1).
 */
@Entity
@Table(name = "attractions")
@Getter
@Setter
@NoArgsConstructor
public class Attraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", nullable = false)
    private Region region;

    @Lob
    private String description;

    /** 쉼표로 이어 붙인 태그 (데모의 AppUser.experienceTags와 같은 방식) */
    private String tags;

    private Double lat;

    private Double lng;

    /** TourAPI 등 외부 소스의 원본 콘텐츠 ID. 동기화 재실행 시 중복 방지용 */
    @Column(name = "source_content_id")
    private String sourceContentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "embedding_status", nullable = false, length = 20)
    private EmbeddingStatus embeddingStatus = EmbeddingStatus.PENDING;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public List<String> tagList() {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
