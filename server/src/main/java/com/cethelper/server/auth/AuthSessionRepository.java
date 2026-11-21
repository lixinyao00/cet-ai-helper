package com.cethelper.server.auth;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {

    Optional<AuthSession> findByTokenDigest(String tokenDigest);

    /** 原子撤销：只对仍未撤销的会话生效，重复退出不会失败。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AuthSession s set s.revokedAt = :revokedAt where s.id = :id and s.revokedAt is null")
    int revoke(@Param("id") Long id, @Param("revokedAt") Instant revokedAt);
}
