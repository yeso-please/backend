package com.yeso.backend.auth.infrastructure;

import com.yeso.backend.auth.domain.SocialAccount;
import com.yeso.backend.auth.domain.SocialProvider;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

    @EntityGraph(attributePaths = "user")
    Optional<SocialAccount> findByProviderAndProviderUserId(SocialProvider provider, String providerUserId);

    @Query("select s.provider from SocialAccount s where s.user.id = :userId order by s.provider")
    List<SocialProvider> findProvidersByUserId(@Param("userId") Long userId);
}
