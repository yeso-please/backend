package com.yeso.backend.profile.infrastructure;

import com.yeso.backend.profile.domain.Friendship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 두 사용자 id는 항상 {@code low < high}로 정규화해서 넘긴다. */
public interface FriendshipRepository extends JpaRepository<Friendship, Long> {

    /** 같은 쌍의 동시 수락에도 한 행만 남긴다. 새로 만들었으면 1, 이미 있으면 0. */
    @Modifying
    @Query(value = """
            insert into {h-schema}friendships (user_low_id, user_high_id, requested_by_user_id, status, created_at)
            values (:low, :high, :requestedBy, 'ACCEPTED', :now)
            on conflict (user_low_id, user_high_id) do nothing
            """, nativeQuery = true)
    int insertAcceptedIfAbsent(
            @Param("low") Long low, @Param("high") Long high,
            @Param("requestedBy") Long requestedBy, @Param("now") LocalDateTime now);

    @Query("""
            select f from Friendship f join fetch f.userLow join fetch f.userHigh
            where f.userLow.id = :low and f.userHigh.id = :high and f.status = com.yeso.backend.profile.domain.FriendshipStatus.ACCEPTED
            """)
    Optional<Friendship> findAccepted(@Param("low") Long low, @Param("high") Long high);

    @Query("""
            select f from Friendship f join fetch f.userLow join fetch f.userHigh
            where (f.userLow.id = :userId or f.userHigh.id = :userId)
              and f.status = com.yeso.backend.profile.domain.FriendshipStatus.ACCEPTED
            order by f.createdAt desc, f.id desc
            """)
    List<Friendship> findAcceptedOf(@Param("userId") Long userId);

    @Modifying
    @Query("""
            delete from Friendship f
            where f.userLow.id = :low and f.userHigh.id = :high
              and f.status = com.yeso.backend.profile.domain.FriendshipStatus.ACCEPTED
            """)
    int deleteAccepted(@Param("low") Long low, @Param("high") Long high);
}
