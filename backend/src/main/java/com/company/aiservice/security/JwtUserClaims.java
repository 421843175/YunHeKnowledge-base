package com.company.aiservice.security;

import io.jsonwebtoken.Claims;

public record JwtUserClaims(
        int userId,
        String username,
        int role,
        int enterpriseId
) {

    public static JwtUserClaims from(Claims claims) {
        return new JwtUserClaims(
                readInt(claims, "user_id"),
                String.valueOf(claims.getOrDefault("username", "")),
                readInt(claims, "role"),
                readInt(claims, "enterprise_id")
        );
    }

    private static int readInt(Claims claims, String key) {
        Object value = claims.get(key);
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}

