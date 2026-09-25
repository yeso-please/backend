package com.yeso.backend.profile.infrastructure;

import com.yeso.backend.profile.domain.FriendLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FriendLinkRepository extends JpaRepository<FriendLink, Long> {

    Optional<FriendLink> findByTokenHash(String tokenHash);

    List<FriendLink> findByCreatedByIdOrderByCreatedAtDescIdDesc(Long userId);
}
