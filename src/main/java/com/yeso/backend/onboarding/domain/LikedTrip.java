package com.yeso.backend.onboarding.domain;

import com.yeso.backend.domain.Region;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

/** 좋았던 여행지 한 건. 같은 submission 안에서 같은 지역은 한 번만 허용한다(DB unique). */
@Entity
@Table(name = "liked_trips")
@Getter
@Setter
@NoArgsConstructor
public class LikedTrip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id", nullable = false)
    private OnboardingSubmission submission;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", nullable = false)
    private Region region;

    @Column(length = 500)
    private String note;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> tags = List.of();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public LikedTrip(OnboardingSubmission submission, Region region, String note, List<String> tags) {
        this.submission = submission;
        this.region = region;
        this.note = note;
        this.tags = tags;
    }
}
