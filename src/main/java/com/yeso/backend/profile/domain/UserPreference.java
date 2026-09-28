package com.yeso.backend.profile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/** 회원 설정. 설정을 바꾼 적 없는 회원은 행이 없고 기본값으로 본다. */
@Entity
@Table(name = "user_preferences")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPreference implements Persistable<Long> {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "course_taste_mode", nullable = false, length = 20)
    private CourseTasteMode courseTasteMode = CourseTasteMode.TASTE;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Transient
    private boolean isNew;

    public static UserPreference defaultsFor(Long userId) {
        UserPreference preference = new UserPreference();
        preference.userId = userId;
        preference.isNew = true;
        return preference;
    }

    public void changeCourseTasteMode(CourseTasteMode mode) {
        this.courseTasteMode = mode;
    }

    @Override
    public Long getId() {
        return userId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
