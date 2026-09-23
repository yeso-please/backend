package com.yeso.backend.profile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;

/**
 * 비회원 참여자(guest)의 취향 임베딩. {@link UserTasteVector}와 같은 목적이며 PK가 users가 아니라
 * trip_participants를 가리킨다는 점만 다르다. PK를 미리 채워 저장하므로 {@link UserTasteVector}와
 * 같은 이유로 {@link Persistable}을 구현한다(그렇지 않으면 새 row도 save()가 merge()를 시도해 실패한다).
 */
@Entity
@Table(name = "guest_taste_vectors")
@Getter
@Setter
@NoArgsConstructor
public class GuestTasteVector implements Persistable<Long> {

    @Id
    @Column(name = "guest_participant_id")
    private Long guestParticipantId;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] embedding;

    @Column(nullable = false)
    private int dimension;

    @Column(name = "profile_text", nullable = false, columnDefinition = "text")
    private String profileText;

    @Column(name = "model_version", nullable = false)
    private String modelVersion;

    @Column(name = "template_version", nullable = false)
    private int templateVersion;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Transient
    private boolean isNew = true;

    public GuestTasteVector(
            Long guestParticipantId, byte[] embedding, int dimension,
            String profileText, String modelVersion, int templateVersion) {
        this.guestParticipantId = guestParticipantId;
        this.embedding = embedding;
        this.dimension = dimension;
        this.profileText = profileText;
        this.modelVersion = modelVersion;
        this.templateVersion = templateVersion;
    }

    @Override
    public Long getId() {
        return guestParticipantId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
