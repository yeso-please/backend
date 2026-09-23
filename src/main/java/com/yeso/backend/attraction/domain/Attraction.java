package com.yeso.backend.attraction.domain;

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
import com.yeso.backend.shared.persistence.BaseTimeEntity;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 관광지. description은 TourAPI 원천 특성상 상당수 비어 있다
 * (demo 레포 실측: 전체의 15.5%만 보유 — DEMO-REFERENCE.md 참고).
 * 임베딩 텍스트 합성(§3.1)은 이 공백을 항상 전제로 폴백을 둔다.
 *
 * embeddingStatus는 신규 등록·description 갱신 시 PENDING으로 리셋되고,
 * 배치가 attraction_embeddings를 채우면 DONE으로 바뀐다(§3.1).
 *
 * name/category/region/tags/description은 임베딩 텍스트를 구성하는 필드라
 * Lombok의 무조건적 setter를 안 쓰고 {@link #updateEmbeddableContent}로만 바꾼다 —
 * 일반 setter로 값을 바꾸면 embeddingStatus가 DONE으로 남아있는데 실제 텍스트는
 * 달라진 상태(재추천에 옛 임베딩이 계속 쓰임)가 될 수 있기 때문이다.
 */
@Entity
@Table(
        name = "attractions",
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(
                columnNames = {"source_system", "source_content_id"})
)
@Getter
@NoArgsConstructor
public class Attraction extends BaseTimeEntity {

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

    @Column(columnDefinition = "text")
    private String description;

    /** 쉼표로 이어 붙인 태그 (데모의 AppUser.experienceTags와 같은 방식) */
    private String tags;

    @Setter
    private Double lat;

    @Setter
    private Double lng;

    /**
     * 외부 원천과 원본 콘텐츠 ID의 조합이 동기화 멱등 키다.
     */
    @Setter
    @Column(name = "source_system", nullable = false, length = 30)
    private String sourceSystem = "TOUR_API";

    @Setter
    @Column(name = "source_content_id", length = 100)
    private String sourceContentId;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "embedding_status", nullable = false, length = 20)
    private EmbeddingStatus embeddingStatus = EmbeddingStatus.PENDING;

    /**
     * 임베딩 텍스트에 들어가는 필드를 한 번에 갱신. 실제로 값이 하나라도 바뀌면
     * embeddingStatus를 PENDING으로 리셋해 배치가 재임베딩하도록 한다.
     */
    public void updateEmbeddableContent(String name, String category, Region region, String tags, String description) {
        boolean changed = !Objects.equals(this.name, name)
                || !Objects.equals(this.category, category)
                || !Objects.equals(this.region, region)
                || !Objects.equals(this.tags, tags)
                || !Objects.equals(this.description, description);
        this.name = name;
        this.category = category;
        this.region = region;
        this.tags = tags;
        this.description = description;
        if (changed) {
            this.embeddingStatus = EmbeddingStatus.PENDING;
        }
    }

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
