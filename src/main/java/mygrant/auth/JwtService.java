package mygrant.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.time.Instant;
import java.util.UUID;

import mygrant.user.User;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMillis;
    private final long refreshExpirationMillis;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms:900000}") long expirationMillis,
            @Value("${jwt.refresh-expiration-ms:604800000}") long refreshExpirationMillis) {
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.expirationMillis = expirationMillis;
        this.refreshExpirationMillis = refreshExpirationMillis;
    }

    /** Kept for compatibility with older integrations; new tokens should use the User overload. */
    public String generateToken(String subject) {
        return generate(subject, null, "access");
    }

    public String generateAccessToken(User user) {
        return generate(user.getEmail(), user.getRole().name(), "access");
    }

    public String generateRefreshToken(User user) {
        return generate(user.getEmail(), user.getRole().name(), "refresh", refreshExpirationMillis,
                UUID.randomUUID().toString());
    }

    private String generate(String subject, String role, String tokenType) {
        return generate(subject, role, tokenType, expirationMillis);
    }

    private String generate(String subject, String role, String tokenType, long lifetimeMillis) {
        return generate(subject, role, tokenType, lifetimeMillis, null);
    }

    private String generate(String subject, String role, String tokenType, long lifetimeMillis,
            String tokenId) {
        Date issuedAt = new Date();
        Date expiration = new Date(issuedAt.getTime() + lifetimeMillis);

        var builder = Jwts.builder()
                .subject(subject)
                .claim("type", tokenType)
                .issuedAt(issuedAt)
                .expiration(expiration)
                .signWith(signingKey);
        if (tokenId != null) {
            builder.id(tokenId);
        }
        if (role != null) {
            builder.claim("role", role);
        }
        return builder.compact();
    }

    public String generateStateToken() {
        Date issuedAt = new Date();
        Date expiration = new Date(issuedAt.getTime() + 10 * 60 * 1000L);
        return Jwts.builder()
                .subject("workos-oauth-state")
                .issuedAt(issuedAt)
                .expiration(expiration)
                .signWith(signingKey)
                .compact();
    }

    public boolean isValidStateToken(String token) {
        return isTokenValid(token, "workos-oauth-state");
    }

    public String extractSubject(String token) {
        return parseClaims(token).getSubject();
    }

    public String extractRole(String token) {
        return parseClaims(token).get("role", String.class);
    }

    public String extractTokenId(String token) {
        return parseClaims(token).getId();
    }

    public Instant extractExpiration(String token) {
        return parseClaims(token).getExpiration().toInstant();
    }

    public boolean isAccessTokenValid(String token, String expectedSubject) {
        return isTokenValid(token, expectedSubject, "access");
    }

    public boolean isRefreshTokenValid(String token, String expectedSubject) {
        return isTokenValid(token, expectedSubject, "refresh");
    }

    public boolean isTokenValid(String token, String expectedSubject) {
        return isTokenValid(token, expectedSubject, null);
    }

    private boolean isTokenValid(String token, String expectedSubject, String expectedType) {
        try {
            Claims claims = parseClaims(token);
            return expectedSubject.equals(claims.getSubject())
                    && (expectedType == null || expectedType.equals(claims.get("type", String.class)))
                    && claims.getExpiration().after(new Date());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
