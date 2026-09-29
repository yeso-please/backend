package com.yeso.backend.trip.domain;

import com.yeso.backend.auth.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "diary_taste_signals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DiaryTasteSignal {

    @Id
    @Column(name = "diary_id")
    private Long diaryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_user_id", nullable = false)
    private User author;

    @Column(name = "region_sig_cd", length = 5)
    private String regionSigCd;

    private Short satisfaction;

    @Column(name = "experience_tags", nullable = false, columnDefinition = "text")
    private String experienceTagsJson;

    public DiaryTasteSignal(Long diaryId, User author, String regionSigCd, Short satisfaction, String experienceTagsJson) {
        this.diaryId = diaryId;
        this.author = author;
        this.regionSigCd = regionSigCd;
        this.satisfaction = satisfaction;
        this.experienceTagsJson = experienceTagsJson;
    }
}
