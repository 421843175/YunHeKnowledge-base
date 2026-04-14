package com.company.aiservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

@Component
public class JwtTokenService {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String TOKEN_PREFIX = "Bearer ";

    private final SecretKey secretKey;

    public JwtTokenService(@Value("${security.jwt.secret:default_secret_key_at_least_32_bytes_123}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public JwtUserClaims parseRequest(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header == null || header.isBlank()) {
            throw new JwtAuthException("missing authorization header");
        }
        if (!header.startsWith(TOKEN_PREFIX)) {
            throw new JwtAuthException("malformed token format");
        }

        String token = header.substring(TOKEN_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new JwtAuthException("malformed token format");
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return JwtUserClaims.from(claims);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new JwtAuthException("invalid token", ex);
        }
    }
}

