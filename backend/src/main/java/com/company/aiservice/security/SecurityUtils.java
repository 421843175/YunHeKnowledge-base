package com.company.aiservice.security;

import jakarta.servlet.http.HttpServletRequest;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static JwtUserClaims getRequiredClaims(HttpServletRequest request) {
        Object claims = request.getAttribute(SecurityConstants.REQUEST_CLAIMS_KEY);
        if (claims instanceof JwtUserClaims jwtUserClaims) {
            return jwtUserClaims;
        }
        throw new JwtAuthException("missing jwt claims");
    }
}

