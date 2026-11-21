-- CET 可选 MySQL 存储：一次性建表脚本（MySQL 8.0+）。
--
-- 用法（由 DBA 在【全新的独立空 schema】中执行一次）：
--   1. 先创建 schema，字符集/排序规则必须区分大小写：
--        CREATE DATABASE <schema> CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
--   2. 再在该 schema 内执行本文件（mysql 客户端或公司变更单均可）：
--        mysql --host=127.0.0.1 --user=<dba_user> --password <schema> < db/mysql/schema.sql
--
-- 说明：
--   * 本脚本只创建两张表，不包含 DROP、TRUNCATE、DELETE、UPDATE、UPSERT；
--     表已存在时会直接报错，不会覆盖任何既有数据。
--   * utf8mb4_bin 让 username 的唯一约束保持与 H2 相同的大小写敏感行为：
--     Stu_a 与 stu_a 是两个不同账号。不要改成 utf8mb4_0900_ai_ci 等不区分大小写的排序规则。
--   * 应用以 mysql profile 启动时只做 JPA validate，不会自动建表或改表。
--   * 时间列统一为 UTC 的 DATETIME(6)，与 Hibernate 的 Instant 映射及
--     MySQL 会话 time_zone='+00:00' 配合；请勿改成 TIMESTAMP（受 2038 年限制）。
--   * id 为自增主键；迁移工具会保留原 ID，InnoDB 会把下一个自增值推进到 max(id)+1。
--   * token_digest 固定为 SHA-256 的 64 位小写十六进制；password_hash 为 BCrypt 文本。
--   * 建议应用账号只授予这两张表的 SELECT/INSERT/UPDATE，不授予 DELETE、DDL、DROP、GRANT；
--     当前业务没有删除记录的操作（退出登录只更新 revoked_at）。

CREATE TABLE student (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(32)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_student_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE auth_session (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    student_id   BIGINT      NOT NULL,
    token_digest VARCHAR(64) NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    expires_at   DATETIME(6) NOT NULL,
    revoked_at   DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_session_token_digest (token_digest),
    KEY idx_auth_session_student (student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
