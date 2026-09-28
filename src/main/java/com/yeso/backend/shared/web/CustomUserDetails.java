package com.yeso.backend.shared.web;

import com.yeso.backend.shared.security.AuthenticatedUserPrincipal;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

/**
 * JWT에서 파싱한 인증 정보만 담는다 — 요청마다 DB를 조회하지 않기 위해
 * User 엔티티를 그대로 감싸지 않고 userId/email만 원시 값으로 들고 있는다.
 */
public record CustomUserDetails(
        Long userId,
        String email,
        Collection<? extends GrantedAuthority> authorities
) implements UserDetails, AuthenticatedUserPrincipal {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return "";
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
