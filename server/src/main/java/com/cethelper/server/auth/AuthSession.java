package com.cethelper.server.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * 登录会话：一行对应一个已签发的 opaque token。
 *
 * <p>只保存 token 的 SHA-256 摘要，数据库泄露也无法还原出可用的凭证。</p>
 */
@Entity
@Table(
        name = "auth_session",
        uniqueConstraints = @UniqueConstraint(name = "uk_auth_session_token_digest", columnNames = "token_digest"),
        indexes = @Index(name = "idx_auth_session_student", columnList = "student_id"))
public class AuthSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "token_digest", nullable = false, length = 64)
    private String tokenDigest;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 退出登录时写入撤销时间；非空即失效。 */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected AuthSession() {
    }

    public AuthSession(Long studentId, String tokenDigest, Instant createdAt, Instant expiresAt) {
        this.studentId = studentId;
        this.tokenDigest = tokenDigest;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** 未撤销且未过期才算有效。 */
    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public Long getId() {
        return id;
    }

    public Long getStudentId() {
        return studentId;
    }

    public String getTokenDigest() {
        return tokenDigest;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
