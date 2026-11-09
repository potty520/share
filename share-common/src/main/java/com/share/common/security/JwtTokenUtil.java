package com.share.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发与校验（HS256）。
 * <p>access token 默认 15 分钟（文档 6.4 节），refresh token 默认 7 天。</p>
 */
@Component
public class JwtTokenUtil {

    private static final String CLAIM_USER_ID = "uid";
    private static final String CLAIM_REAL_NAME = "rn";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_DEPT = "dept";
    private static final String CLAIM_TOKEN_TYPE = "typ";
    private static final String CLAIM_MFA = "mfa";
    private static final String CLAIM_KEY = "key";

    private final SecretKey key;
    private final long accessMinutes;
    private final long refreshDays;
    private final String apisixKey;

    public JwtTokenUtil(@Value("${share.jwt.secret:share-exchange-platform-default-secret-key-2026-please-change}") String secret,
                        @Value("${share.jwt.access-minutes:15}") long accessMinutes,
                        @Value("${share.jwt.refresh-days:7}") long refreshDays,
                        @Value("${share.gateway.apisix.jwt-consumer:share_platform}") String apisixKey) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("share.jwt.secret 长度必须不小于 32 字节（HS256 要求）");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.accessMinutes = accessMinutes;
        this.refreshDays = refreshDays;
        this.apisixKey = apisixKey;
    }

    public String createAccessToken(LoginUser user) {
        return create(user, "access", Instant.now().plusSeconds(accessMinutes * 60));
    }

    public String createRefreshToken(LoginUser user) {
        return create(user, "refresh", Instant.now().plusSeconds(refreshDays * 24 * 3600));
    }

    public long getAccessMinutes() {
        return accessMinutes;
    }

    private String create(LoginUser user, String type, Instant expireAt) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim(CLAIM_USER_ID, user.getUserId())
                .claim(CLAIM_REAL_NAME, user.getRealName())
                .claim(CLAIM_ROLE, user.getRoleCode())
                .claim(CLAIM_DEPT, user.getDeptId())
                .claim(CLAIM_TOKEN_TYPE, type)
                .claim(CLAIM_MFA, user.isMfaPassed())
                .claim(CLAIM_KEY, apisixKey)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expireAt))
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验 token。
     *
     * @throws io.jsonwebtoken.JwtException 令牌非法或过期
     */
    public LoginUser parse(String token) {
        Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        Claims c = jws.getPayload();
        LoginUser user = new LoginUser();
        user.setUsername(c.getSubject());
        user.setUserId(longValue(c.get(CLAIM_USER_ID)));
        user.setRealName(c.get(CLAIM_REAL_NAME, String.class));
        user.setRoleCode(c.get(CLAIM_ROLE, String.class));
        user.setDeptId(longValue(c.get(CLAIM_DEPT)));
        user.setTokenType(c.get(CLAIM_TOKEN_TYPE, String.class));
        user.setMfaPassed(Boolean.TRUE.equals(c.get(CLAIM_MFA, Boolean.class)));
        return user;
    }

    private Long longValue(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(v.toString());
    }
}
