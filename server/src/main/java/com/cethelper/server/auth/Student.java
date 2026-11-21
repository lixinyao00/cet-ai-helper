package com.cethelper.server.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * 学生账号。
 *
 * <p>用户名的唯一性由数据库唯一约束 {@code uk_student_username} 保证，
 * 因此并发注册同一用户名时也只会有一个成功。</p>
 */
@Entity
@Table(name = "student", uniqueConstraints = @UniqueConstraint(name = "uk_student_username", columnNames = "username"))
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, length = 32)
    private String username;

    /** BCrypt 哈希，绝不保存或输出明文口令。 */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Student() {
    }

    public Student(String username, String passwordHash, Instant createdAt) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
