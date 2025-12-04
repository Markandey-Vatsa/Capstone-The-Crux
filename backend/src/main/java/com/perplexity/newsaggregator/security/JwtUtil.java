package com.perplexity.newsaggregator.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;

@Service
public class JwtUtil {
    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);
    
    @Value("${jwt.secret}")
    private String secret;
    
    @Value("${jwt.expiration}")
    private Long expiration;
    
    // Generate secret key from the configured secret
    private SecretKey getSigningKey() {
        String raw = secret == null ? "" : secret.trim();
        byte[] keyBytes;
        // Attempt Base64 decode if it looks like Base64; otherwise use raw bytes
        if (raw.matches("^[A-Za-z0-9+/=]+$") && raw.length() % 4 == 0) {
            try {
                keyBytes = Decoders.BASE64.decode(raw);
            } catch (Exception e) {
                log.warn("JWT secret looked like Base64 but failed to decode; using UTF-8 bytes.");
                keyBytes = raw.getBytes(StandardCharsets.UTF_8);
            }
        } else {
            keyBytes = raw.getBytes(StandardCharsets.UTF_8);
        }
        if (keyBytes.length < 32) {
            log.error("JWT secret too short ({} bytes). Minimum 32 bytes recommended.", keyBytes.length);
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    @PostConstruct
    private void validateSecret() {
        if (secret == null || secret.isBlank()) {
            log.error("jwt.secret property is missing or blank. Tokens will fail validation.");
        } else {
            log.info("JWT secret loaded (length: {} chars). Using expiration {} ms", secret.trim().length(), expiration);
        }
    }
    
    // Generate JWT token for authenticated user
    public String generateToken(String username) {
        return createToken(username);
    }
    
    // Create JWT token with username and expiration
    private String createToken(String username) {
    return Jwts.builder()
        .subject(username)
        .issuedAt(new Date(System.currentTimeMillis()))
        .expiration(new Date(System.currentTimeMillis() + expiration))
        .signWith(getSigningKey()) // algorithm inferred from key length
        .compact();
    }
    
    // Extract username from JWT token
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }
    
    // Extract expiration date from JWT token
    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }
    
    // Extract specific claim from JWT token
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }
    
    // Extract all claims from JWT token
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
    
    // Check if JWT token is expired
    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }
    
    // Validate JWT token against username and expiration
    public Boolean validateToken(String token, String username) {
        final String extractedUsername = extractUsername(token);
        return (extractedUsername.equals(username) && !isTokenExpired(token));
    }
}
