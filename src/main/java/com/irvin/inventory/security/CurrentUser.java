package com.irvin.inventory.security;

import com.irvin.inventory.user.Role;
import org.springframework.security.oauth2.jwt.Jwt;

// Authenticated user as stated by the access token (sub = user id, role claim).
public record CurrentUser(Long id, Role role) {

    public static CurrentUser from(Jwt jwt) {
        return new CurrentUser(Long.valueOf(jwt.getSubject()), Role.valueOf(jwt.getClaimAsString(TokenService.ROLE_CLAIM)));
    }

    public boolean isManager() {
        return role.isManager();
    }
}
