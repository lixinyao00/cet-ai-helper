import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 一次性 H2 → MySQL 迁移工具（JDK 21 单文件源码，直接运行，无需先编译）。
 *
 * <p>从只读打开的 CET 文件型 H2 库读取 student 与 auth_session，
 * 按原 ID 写入一个全新的、已经用 db/mysql/schema.sql 建好表的独立 MySQL schema。</p>
 *
 * <p>安全边界：</p>
 * <ul>
 *   <li>H2 以 {@code ACCESS_MODE_DATA=r} 打开；发现 {@code .lock.db} 时拒绝运行；</li>
 *   <li>MySQL 侧使用 schema 专有 named lock，防止两个迁移同时进行；</li>
 *   <li>单事务内先确认目标两表为空，再插入；任何校验失败都回滚；</li>
 *   <li>只执行 SELECT / INSERT；绝不执行 DROP、TRUNCATE、DELETE、UPDATE、UPSERT 或 DDL；</li>
 *   <li>提交前逐行核对行数、主键、全部字段和 UTC 微秒时间；常规输出不打印口令哈希、token 摘要或密码。</li>
 * </ul>
 */
public final class MigrateH2ToMysql {

    private static final int BATCH_SIZE = 500;
    private static final String LOCK_PREFIX = "cet_migrate:";
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{2,31}$");
    private static final Pattern TOKEN_DIGEST_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> SYSTEM_SCHEMAS =
            Set.of("mysql", "information_schema", "performance_schema", "sys");

    private static final String USAGE = """
            用法：
              java -cp "<H2 与 MySQL JDBC 驱动的 classpath>" tools/MigrateH2ToMysql.java \\
                  --h2 data/cet \\
                  --mysql-url 'jdbc:mysql://127.0.0.1:3306/<schema>' \\
                  --mysql-schema <schema> [--dry-run]

            参数：
              --h2 <路径>          H2 文件库基名（可带或不带 .mv.db）；只读打开，服务必须先正常关机或使用拷贝件
              --mysql-url <URL>    目标 MySQL JDBC URL，只允许 jdbc:mysql://；建议走环回地址或 SSH 隧道
              --mysql-schema <名>  目标独立空 schema，必须与 URL 指向的当前 schema 一致，且不能是系统库
              --dry-run            只校验源库、目标为空和表结构，不写入任何数据
              --help               显示本说明

            凭据只从环境变量读取，不出现在命令行：
              CET_DB_USERNAME / CET_DB_PASSWORD   MySQL 低权限账号（只授予两张表的 SELECT/INSERT/UPDATE 即可）
              CET_H2_USERNAME / CET_H2_PASSWORD   H2 账号，默认 sa / 空口令

            示例中的主机、schema、账号均为占位符；请勿把真实凭据写进命令行或文档。
            """;

    private MigrateH2ToMysql() {
    }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            if (options.help()) {
                System.out.print(USAGE);
                return;
            }
            run(options);
        } catch (UsageException e) {
            System.err.println("参数错误：" + e.getMessage());
            System.err.println();
            System.err.print(USAGE);
            System.exit(2);
        } catch (MigrationFailure e) {
            System.err.println(e.getMessage());
            System.err.println("请保持 H2 文件现状；本工具不会删除或覆盖目标库既有记录。");
            System.exit(1);
        } catch (Exception e) {
            System.err.println("迁移失败（尚未开始写目标库）：" + describe(e));
            System.err.println("请保持 H2 文件与服务现状，人工核对后再重试；本工具不会删除或覆盖目标库既有记录。");
            System.exit(1);
        }
    }

    private static void run(Options options) throws Exception {
        String mysqlUser = requireEnv("CET_DB_USERNAME", false);
        String mysqlPassword = requireEnv("CET_DB_PASSWORD", true);
        String h2User = envOrDefault("CET_H2_USERNAME", "sa");
        String h2Password = envOrDefault("CET_H2_PASSWORD", "");

        loadDriver("org.h2.Driver");
        loadDriver("com.mysql.cj.jdbc.Driver");

        Path h2Base = options.h2Base();
        Path h2File = h2Base.resolveSibling(h2Base.getFileName() + ".mv.db");
        Path h2Lock = h2Base.resolveSibling(h2Base.getFileName() + ".lock.db");

        if (!Files.isRegularFile(h2File)) {
            throw new UsageException("找不到 H2 文件库：" + h2File + "（--h2 指向不带 .mv.db 的基名）");
        }
        if (Files.exists(h2Lock)) {
            throw new MigrationFailure(
                    "检测到 H2 锁文件 " + h2Lock + "，数据库可能仍被服务打开。请先正常停止服务，"
                            + "或复制一份已正常关机后的 .mv.db 再迁移。");
        }

        String h2Url = "jdbc:h2:file:" + h2Base + ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE";

        try (Connection h2 = DriverManager.getConnection(h2Url, h2User, h2Password)) {
            h2.setReadOnly(true);

            List<StudentRow> students = readSourceStudents(h2);
            List<SessionRow> sessions = readSourceSessions(h2);
            validateSource(students, sessions);

            try (Connection mysql = DriverManager.getConnection(
                    options.mysqlUrl(), mysqlUser, mysqlPassword)) {
                // 串行化事务：配合“操作窗口内没有应用写入”的前提，避免迁移过程中插入新账号。
                mysql.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                mysql.setAutoCommit(false);
                try (var statement = mysql.createStatement()) {
                    // 与 mysql profile 一致：会话时区固定 UTC；下面仍以 LocalDateTime(UTC) 绑定，避免驱动换算。
                    statement.execute("SET time_zone = '+00:00'");
                }

                String catalog = mysql.getCatalog();
                if (catalog == null || !options.mysqlSchema().equalsIgnoreCase(catalog)) {
                    throw new MigrationFailure(
                            "JDBC URL 当前 schema（" + catalog + "）与 --mysql-schema（"
                                    + options.mysqlSchema() + "）不一致；请把 URL 明确指向目标独立 schema。");
                }

                String lockName = lockName(options.mysqlSchema());
                if (!acquireLock(mysql, lockName)) {
                    throw new MigrationFailure(
                            "未取得迁移锁 " + lockName + "：可能有另一个迁移正在运行。请等待其结束后重试。");
                }
                boolean committed = false;
                boolean commitAttempted = false;
                try {
                    requireTargetSchema(mysql, options.mysqlSchema());
                    requireTargetEmpty(mysql, options.mysqlSchema());

                    if (options.dryRun()) {
                        System.out.println("dry-run：H2 源库 " + h2File + "（只读）");
                        printSourceSummary(students, sessions);
                        System.out.println("MySQL 目标：schema `" + options.mysqlSchema()
                                + "` 表结构校验通过，两表为空；未写入任何数据。");
                        return;
                    }

                    System.out.println("H2 源库：" + h2File + "（只读）");
                    printSourceSummary(students, sessions);
                    System.out.println("MySQL 目标：schema `" + options.mysqlSchema()
                            + "` 表结构校验通过，迁移前两表为空");
                    System.out.println("写入中：保留原 ID，单事务提交……");

                    insertStudents(mysql, students);
                    insertSessions(mysql, sessions);
                    verifyTarget(mysql, students, sessions);

                    commitAttempted = true;
                    mysql.commit();
                    committed = true;
                    System.out.println("校验通过：行数、主键、全部字段与 UTC 微秒时间均与源库一致。");
                    System.out.println("已提交：" + students.size() + " 个学生、" + sessions.size()
                            + " 个会话。请保留 H2 文件备份，并用 mysql profile 启动服务（JPA validate）。");
                } catch (Exception e) {
                    if (committed) {
                        throw new MigrationFailure(
                                "目标库已提交，但后续输出失败；请核对两表行数：" + describe(e), e);
                    }
                    boolean rolledBack = false;
                    try {
                        mysql.rollback();
                        rolledBack = true;
                    } catch (SQLException rollbackFailure) {
                        e.addSuppressed(rollbackFailure);
                    }
                    if (commitAttempted) {
                        // commit() 抛异常可能发生在服务端已提交之后，不能断言成功或失败。
                        throw new MigrationFailure(
                                "迁移失败：COMMIT 阶段连接异常，无法确认目标库是否已提交。"
                                        + "请先核对两表行数与源库是否一致，再决定是否重试：" + describe(e), e);
                    }
                    if (!rolledBack) {
                        throw new MigrationFailure(
                                "迁移失败：未能确认回滚完成，请核对目标两表后再重试：" + describe(e), e);
                    }
                    throw new MigrationFailure(
                            "迁移失败，目标库事务已回滚，未提交任何记录：" + describe(e), e);
                } finally {
                    releaseLock(mysql, lockName);
                }
            }
        }
    }

    private static List<StudentRow> readSourceStudents(Connection h2) {
        String sql = "select id, username, password_hash, created_at from student order by id";
        List<StudentRow> rows = new ArrayList<>();
        try (var statement = h2.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new StudentRow(rs.getLong(1), rs.getString(2), rs.getString(3), readInstant(rs, 4)));
            }
            return rows;
        } catch (SQLException e) {
            throw new MigrationFailure(
                    "读取 H2 的 student 表失败；请确认 --h2 指向 CET 的 H2 文件库。", e);
        }
    }

    private static List<SessionRow> readSourceSessions(Connection h2) {
        String sql = "select id, student_id, token_digest, created_at, expires_at, revoked_at "
                + "from auth_session order by id";
        List<SessionRow> rows = new ArrayList<>();
        try (var statement = h2.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new SessionRow(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getString(3),
                        readInstant(rs, 4),
                        readInstant(rs, 5),
                        readInstant(rs, 6)));
            }
            return rows;
        } catch (SQLException e) {
            throw new MigrationFailure(
                    "读取 H2 的 auth_session 表失败；请确认 --h2 指向 CET 的 H2 文件库。", e);
        }
    }

    private static Instant readInstant(ResultSet rs, int column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return zonedDateTime.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        // 兼容早期没有时区列的 H2 库：Hibernate 存的是 UTC，按 UTC 解释。
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toInstant(ZoneOffset.UTC);
        }
        throw new MigrationFailure("第 " + column + " 列不是可识别的时间类型：" + value.getClass().getName());
    }

    private static void validateSource(List<StudentRow> students, List<SessionRow> sessions) {
        Set<Long> studentIds = new HashSet<>();
        long previousId = 0;
        for (StudentRow row : students) {
            requireIdInOrder("student", row.id(), previousId);
            previousId = row.id();
            studentIds.add(row.id());
            if (row.username() == null || !USERNAME_PATTERN.matcher(row.username()).matches()) {
                throw invalidSource("student", row.id(), "username 不符合应用的 3–32 位字母开头规则");
            }
            if (row.passwordHash() == null || row.passwordHash().isEmpty()
                    || row.passwordHash().length() > 100) {
                throw invalidSource("student", row.id(), "password_hash 为空或超过 100 字符");
            }
            requireMicros("student", row.id(), "created_at", row.createdAt());
        }

        previousId = 0;
        for (SessionRow row : sessions) {
            requireIdInOrder("auth_session", row.id(), previousId);
            previousId = row.id();
            if (row.studentId() <= 0 || !studentIds.contains(row.studentId())) {
                throw invalidSource("auth_session", row.id(), "student_id 在 student 表中不存在");
            }
            if (row.tokenDigest() == null || !TOKEN_DIGEST_PATTERN.matcher(row.tokenDigest()).matches()) {
                throw invalidSource("auth_session", row.id(), "token_digest 不是 64 位小写 SHA-256 十六进制");
            }
            requireMicros("auth_session", row.id(), "created_at", row.createdAt());
            requireMicros("auth_session", row.id(), "expires_at", row.expiresAt());
            if (row.revokedAt() != null) {
                requireMicros("auth_session", row.id(), "revoked_at", row.revokedAt());
            }
        }
    }

    private static void requireIdInOrder(String table, long id, long previousId) {
        if (id <= 0 || id <= previousId) {
            throw new MigrationFailure("H2 源库 " + table + " 的 ID 不是严格递增的正数，已停止迁移。");
        }
    }

    private static MigrationFailure invalidSource(String table, long id, String reason) {
        return new MigrationFailure("H2 源库 " + table + " id=" + id + " 校验失败：" + reason + "。");
    }

    private static void requireMicros(String table, long id, String column, Instant value) {
        if (value == null) {
            throw invalidSource(table, id, column + " 为空");
        }
        if (value.getNano() % 1000 != 0) {
            throw invalidSource(table, id, column + " 的精度高于微秒，迁移会丢精度");
        }
    }

    private static void insertStudents(Connection mysql, List<StudentRow> rows) throws SQLException {
        String sql = "insert into student (id, username, password_hash, created_at) values (?, ?, ?, ?)";
        try (PreparedStatement ps = mysql.prepareStatement(sql)) {
            int pending = 0;
            for (StudentRow row : rows) {
                ps.setLong(1, row.id());
                ps.setString(2, row.username());
                ps.setString(3, row.passwordHash());
                ps.setObject(4, utcLocal(row.createdAt()));
                ps.addBatch();
                if (++pending == BATCH_SIZE) {
                    ps.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                ps.executeBatch();
            }
        }
    }

    private static void insertSessions(Connection mysql, List<SessionRow> rows) throws SQLException {
        String sql = "insert into auth_session "
                + "(id, student_id, token_digest, created_at, expires_at, revoked_at) "
                + "values (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = mysql.prepareStatement(sql)) {
            int pending = 0;
            for (SessionRow row : rows) {
                ps.setLong(1, row.id());
                ps.setLong(2, row.studentId());
                ps.setString(3, row.tokenDigest());
                ps.setObject(4, utcLocal(row.createdAt()));
                ps.setObject(5, utcLocal(row.expiresAt()));
                if (row.revokedAt() == null) {
                    ps.setNull(6, Types.TIMESTAMP);
                } else {
                    ps.setObject(6, utcLocal(row.revokedAt()));
                }
                ps.addBatch();
                if (++pending == BATCH_SIZE) {
                    ps.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                ps.executeBatch();
            }
        }
    }

    private static void verifyTarget(Connection mysql, List<StudentRow> students, List<SessionRow> sessions)
            throws SQLException {
        requireCount("student", count(mysql, "student"), students.size());
        requireCount("auth_session", count(mysql, "auth_session"), sessions.size());

        List<StudentRow> actualStudents = readTargetStudents(mysql);
        for (int i = 0; i < students.size(); i++) {
            StudentRow expected = students.get(i);
            StudentRow actual = actualStudents.get(i);
            requireSame("student", expected.id(), "id", expected.id(), actual.id());
            requireSame("student", expected.id(), "username", expected.username(), actual.username());
            requireSame("student", expected.id(), "password_hash", expected.passwordHash(), actual.passwordHash());
            requireSameInstant("student", expected.id(), "created_at", expected.createdAt(), actual.createdAt());
        }

        List<SessionRow> actualSessions = readTargetSessions(mysql);
        for (int i = 0; i < sessions.size(); i++) {
            SessionRow expected = sessions.get(i);
            SessionRow actual = actualSessions.get(i);
            requireSame("auth_session", expected.id(), "id", expected.id(), actual.id());
            requireSame("auth_session", expected.id(), "student_id", expected.studentId(), actual.studentId());
            requireSame("auth_session", expected.id(), "token_digest", expected.tokenDigest(), actual.tokenDigest());
            requireSameInstant("auth_session", expected.id(), "created_at", expected.createdAt(), actual.createdAt());
            requireSameInstant("auth_session", expected.id(), "expires_at", expected.expiresAt(), actual.expiresAt());
            requireSameInstant("auth_session", expected.id(), "revoked_at", expected.revokedAt(), actual.revokedAt());
        }
    }

    private static List<StudentRow> readTargetStudents(Connection mysql) throws SQLException {
        String sql = "select id, username, password_hash, created_at from student order by id";
        List<StudentRow> rows = new ArrayList<>();
        try (var statement = mysql.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new StudentRow(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        readLocalInstant(rs, 4)));
            }
            return rows;
        }
    }

    private static List<SessionRow> readTargetSessions(Connection mysql) throws SQLException {
        String sql = "select id, student_id, token_digest, created_at, expires_at, revoked_at "
                + "from auth_session order by id";
        List<SessionRow> rows = new ArrayList<>();
        try (var statement = mysql.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new SessionRow(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getString(3),
                        readLocalInstant(rs, 4),
                        readLocalInstant(rs, 5),
                        readLocalInstant(rs, 6)));
            }
            return rows;
        }
    }

    private static Instant readLocalInstant(ResultSet rs, int column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static void requireSame(String table, long id, String column, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new MigrationFailure(
                    "校验失败：" + table + " id=" + id + " 的 " + column + " 与源库不一致。");
        }
    }

    private static void requireSameInstant(String table, long id, String column, Instant expected, Instant actual) {
        Instant normalizedExpected = expected == null ? null : expected.truncatedTo(ChronoUnit.MICROS);
        Instant normalizedActual = actual == null ? null : actual.truncatedTo(ChronoUnit.MICROS);
        if (!Objects.equals(normalizedExpected, normalizedActual)) {
            throw new MigrationFailure(
                    "校验失败：" + table + " id=" + id + " 的 " + column + "（UTC 微秒）与源库不一致。");
        }
    }

    private static void requireCount(String table, long actual, long expected) {
        if (actual != expected) {
            throw new MigrationFailure(
                    "校验失败：" + table + " 行数为 " + actual + "，预期 " + expected + "。");
        }
    }

    private static void requireTargetEmpty(Connection mysql, String schema) throws SQLException {
        long studentCount = count(mysql, "student");
        long sessionCount = count(mysql, "auth_session");
        if (studentCount != 0 || sessionCount != 0) {
            throw new MigrationFailure(
                    "目标 schema `" + schema + "` 不是空表（student=" + studentCount
                            + "，auth_session=" + sessionCount + "）。本工具禁止覆盖或删除既有记录，已停止。");
        }
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (var statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select count(*) from " + table)) {
            if (!rs.next()) {
                throw new MigrationFailure("count 查询没有返回结果：" + table);
            }
            return rs.getLong(1);
        }
    }

    private static void requireTargetSchema(Connection mysql, String schema) throws SQLException {
        Map<String, ColumnInfo> studentColumns = readColumns(mysql, schema, "student");
        Map<String, ColumnInfo> sessionColumns = readColumns(mysql, schema, "auth_session");
        if (studentColumns.isEmpty()) {
            throw new MigrationFailure(
                    "目标 schema 中找不到 student 表；请先由 DBA 在该独立空 schema 执行 db/mysql/schema.sql。");
        }
        if (sessionColumns.isEmpty()) {
            throw new MigrationFailure(
                    "目标 schema 中找不到 auth_session 表；请先由 DBA 在该独立空 schema 执行 db/mysql/schema.sql。");
        }

        requireEngine(mysql, schema, "student");
        requireEngine(mysql, schema, "auth_session");

        requireColumns("student", studentColumns, List.of(
                new ColumnSpec("id", "bigint", null, null, false, true),
                new ColumnSpec("username", "varchar", 32L, null, false, false),
                new ColumnSpec("password_hash", "varchar", 100L, null, false, false),
                new ColumnSpec("created_at", "datetime", null, 6L, false, false)));
        requireColumns("auth_session", sessionColumns, List.of(
                new ColumnSpec("id", "bigint", null, null, false, true),
                new ColumnSpec("student_id", "bigint", null, null, false, false),
                new ColumnSpec("token_digest", "varchar", 64L, null, false, false),
                new ColumnSpec("created_at", "datetime", null, 6L, false, false),
                new ColumnSpec("expires_at", "datetime", null, 6L, false, false),
                new ColumnSpec("revoked_at", "datetime", null, 6L, true, false)));

        ColumnInfo username = studentColumns.get("username");
        if (!isCaseSensitiveCollation(username.collation())) {
            throw new MigrationFailure(
                    "student.username 的排序规则 `" + username.collation()
                            + "` 不区分大小写；H2 行为要求 Stu_a 与 stu_a 是不同账号。"
                            + "请按 db/mysql/schema.sql 使用 utf8mb4_bin 重建该独立 schema。");
        }

        requireIndex(mysql, schema, "student", "username", true);
        requireIndex(mysql, schema, "auth_session", "token_digest", true);
        requireIndex(mysql, schema, "auth_session", "student_id", null);
    }

    private static Map<String, ColumnInfo> readColumns(Connection mysql, String schema, String table)
            throws SQLException {
        String sql = """
                select COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, DATETIME_PRECISION,
                       IS_NULLABLE, EXTRA, COLLATION_NAME
                from information_schema.COLUMNS
                where TABLE_SCHEMA = ? and TABLE_NAME = ?
                order by ORDINAL_POSITION
                """;
        Map<String, ColumnInfo> columns = new LinkedHashMap<>();
        try (PreparedStatement ps = mysql.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
                    columns.put(name, new ColumnInfo(
                            rs.getString("DATA_TYPE"),
                            nullableLong(rs, "CHARACTER_MAXIMUM_LENGTH"),
                            nullableLong(rs, "DATETIME_PRECISION"),
                            "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")),
                            rs.getString("EXTRA") == null ? "" : rs.getString("EXTRA"),
                            rs.getString("COLLATION_NAME")));
                }
            }
        }
        return columns;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    private static void requireEngine(Connection mysql, String schema, String table) throws SQLException {
        String sql = "select ENGINE from information_schema.TABLES where TABLE_SCHEMA = ? and TABLE_NAME = ?";
        try (PreparedStatement ps = mysql.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new MigrationFailure("目标 schema 中找不到表 " + table + "。");
                }
                String engine = rs.getString(1);
                if (!"InnoDB".equalsIgnoreCase(engine)) {
                    throw new MigrationFailure(
                            "表 " + table + " 的存储引擎是 " + engine + "，无法保证事务回滚；请使用 schema.sql 的 InnoDB 表。");
                }
            }
        }
    }

    private static void requireColumns(String table, Map<String, ColumnInfo> actual, List<ColumnSpec> expected) {
        Set<String> expectedNames = new HashSet<>();
        for (ColumnSpec spec : expected) {
            expectedNames.add(spec.name());
            ColumnInfo column = actual.get(spec.name());
            if (column == null) {
                throw new MigrationFailure("目标表 " + table + " 缺少列 " + spec.name() + "；请先执行 schema.sql。");
            }
            if (!spec.dataType().equalsIgnoreCase(column.dataType())) {
                throw new MigrationFailure("目标表 " + table + "." + spec.name() + " 的类型不是 "
                        + spec.dataType() + "；请使用 schema.sql 重建。");
            }
            if (spec.length() != null && !spec.length().equals(column.length())) {
                throw new MigrationFailure("目标表 " + table + "." + spec.name() + " 的长度应为 "
                        + spec.length() + "；请使用 schema.sql 重建。");
            }
            if (spec.precision() != null && !spec.precision().equals(column.precision())) {
                throw new MigrationFailure("目标表 " + table + "." + spec.name() + " 的精度应为 "
                        + spec.precision() + "；请使用 schema.sql 重建。");
            }
            if (spec.nullable() != column.nullable()) {
                throw new MigrationFailure("目标表 " + table + "." + spec.name() + " 的 NULL 属性不符合 schema.sql。");
            }
            if (spec.autoIncrement()
                    && !column.extra().toLowerCase(Locale.ROOT).contains("auto_increment")) {
                throw new MigrationFailure("目标表 " + table + "." + spec.name() + " 不是自增主键。");
            }
        }
        Set<String> extra = new HashSet<>(actual.keySet());
        extra.removeAll(expectedNames);
        if (!extra.isEmpty()) {
            throw new MigrationFailure(
                    "目标表 " + table + " 含有 schema.sql 之外的列 " + extra + "；为避免写错库，已停止。");
        }
    }

    private static boolean isCaseSensitiveCollation(String collation) {
        if (collation == null) {
            return false;
        }
        String normalized = collation.toLowerCase(Locale.ROOT);
        return normalized.endsWith("_bin") || normalized.endsWith("_cs");
    }

    private static void requireIndex(Connection mysql, String schema, String table, String column, Boolean unique)
            throws SQLException {
        String sql = """
                select count(*) from information_schema.STATISTICS
                where TABLE_SCHEMA = ? and TABLE_NAME = ? and COLUMN_NAME = ?
                """ + (unique == null ? "" : " and NON_UNIQUE = ? ");
        try (PreparedStatement ps = mysql.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            ps.setString(3, column);
            if (unique != null) {
                ps.setInt(4, unique ? 0 : 1);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getLong(1) < 1) {
                    throw new MigrationFailure(
                            "目标表 " + table + " 缺少 " + (Boolean.TRUE.equals(unique) ? "唯一" : "")
                                    + "索引 " + column + "；请先执行 schema.sql。");
                }
            }
        }
    }

    private static String lockName(String schema) {
        String candidate = LOCK_PREFIX + schema;
        if (candidate.length() <= 64) {
            return candidate;
        }
        return LOCK_PREFIX + sha256Hex(schema).substring(0, 64 - LOCK_PREFIX.length());
    }

    private static boolean acquireLock(Connection mysql, String lockName) throws SQLException {
        try (PreparedStatement ps = mysql.prepareStatement("select get_lock(?, 0)")) {
            ps.setString(1, lockName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }

    private static void releaseLock(Connection mysql, String lockName) {
        try (PreparedStatement ps = mysql.prepareStatement("select release_lock(?)")) {
            ps.setString(1, lockName);
            ps.executeQuery().close();
        } catch (SQLException ignored) {
            // 连接关闭时 MySQL 会自动释放 named lock。
        }
    }

    private static LocalDateTime utcLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void printSourceSummary(List<StudentRow> students, List<SessionRow> sessions) {
        System.out.println("  student: " + students.size() + " 行，ID "
                + idRange(students.stream().map(StudentRow::id).toList()));
        System.out.println("  auth_session: " + sessions.size() + " 行，ID "
                + idRange(sessions.stream().map(SessionRow::id).toList()));
    }

    private static String idRange(List<Long> ids) {
        if (ids.isEmpty()) {
            return "（空）";
        }
        return ids.get(0) + ".." + ids.get(ids.size() - 1);
    }

    private static String requireEnv(String name, boolean allowEmpty) {
        String value = System.getenv(name);
        if (value == null || (!allowEmpty && value.isBlank())) {
            throw new UsageException("缺少环境变量 " + name + (allowEmpty ? "（允许空串，但必须显式设置）" : ""));
        }
        return value;
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null ? defaultValue : value;
    }

    private static void loadDriver(String className) {
        try {
            Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new MigrationFailure(
                    "classpath 缺少 JDBC 驱动 " + className + "；请按 README 4.4 生成 migration-classpath.txt 再运行。", e);
        }
    }

    private static String describe(Throwable throwable) {
        StringBuilder message = new StringBuilder();
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 5) {
            if (depth > 0) {
                message.append(" <- ");
            }
            message.append(current.getClass().getSimpleName());
            if (current instanceof SQLException sqlException) {
                // JDBC 原始消息可能带唯一冲突的具体值（用户名、token 摘要等），只保留安全分类。
                if (sqlException.getSQLState() != null) {
                    message.append(" [SQLState=").append(sqlException.getSQLState()).append(']');
                }
                if (sqlException.getErrorCode() != 0) {
                    message.append(" [vendor=").append(sqlException.getErrorCode()).append(']');
                }
            } else if (current instanceof MigrationFailure && current.getMessage() != null) {
                message.append(": ").append(current.getMessage());
            }
            Throwable next = current.getCause() == current ? null : current.getCause();
            current = next;
            depth++;
        }
        return message.toString();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new MigrationFailure("当前 JDK 不支持 SHA-256", e);
        }
    }

    /** 命令行参数。 */
    private record Options(Path h2Base, String mysqlUrl, String mysqlSchema, boolean dryRun, boolean help) {

        static Options parse(String[] args) {
            Path h2Base = null;
            String mysqlUrl = null;
            String mysqlSchema = null;
            boolean dryRun = false;
            boolean help = false;

            for (int i = 0; i < args.length; i++) {
                String argument = args[i];
                if ("--help".equals(argument) || "-h".equals(argument)) {
                    help = true;
                } else if ("--h2".equals(argument)) {
                    h2Base = normalizeH2Base(value(args, ++i, argument));
                } else if ("--mysql-url".equals(argument)) {
                    mysqlUrl = value(args, ++i, argument);
                } else if ("--mysql-schema".equals(argument)) {
                    mysqlSchema = value(args, ++i, argument);
                } else if ("--dry-run".equals(argument)) {
                    dryRun = true;
                } else {
                    throw new UsageException("未知参数：" + argument);
                }
            }

            if (help) {
                return new Options(null, null, null, dryRun, true);
            }
            if (h2Base == null) {
                throw new UsageException("必须提供 --h2");
            }
            if (mysqlUrl == null || !mysqlUrl.startsWith("jdbc:mysql://")) {
                throw new UsageException("--mysql-url 必须是 jdbc:mysql:// 开头的 URL");
            }
            if (mysqlSchema == null || !mysqlSchema.matches("[A-Za-z0-9_]{1,64}")) {
                throw new UsageException("--mysql-schema 只能包含字母、数字、下划线，长度 1–64");
            }
            if (SYSTEM_SCHEMAS.contains(mysqlSchema.toLowerCase(Locale.ROOT))) {
                throw new UsageException("--mysql-schema 不能是 MySQL 系统库：" + mysqlSchema);
            }
            return new Options(h2Base, mysqlUrl, mysqlSchema, dryRun, false);
        }

        private static Path normalizeH2Base(String raw) {
            String value = raw;
            if (value.endsWith(".mv.db")) {
                value = value.substring(0, value.length() - ".mv.db".length());
            }
            Path base = Path.of(value).toAbsolutePath().normalize();
            if (base.getFileName() == null) {
                throw new UsageException("--h2 必须指向数据库基名，而不是目录");
            }
            return base;
        }

        private static String value(String[] args, int index, String argument) {
            if (index >= args.length) {
                throw new UsageException(argument + " 缺少值");
            }
            return args[index];
        }
    }

    private record StudentRow(long id, String username, String passwordHash, Instant createdAt) {
    }

    private record SessionRow(long id, long studentId, String tokenDigest, Instant createdAt, Instant expiresAt,
                              Instant revokedAt) {
    }

    private record ColumnSpec(String name, String dataType, Long length, Long precision, boolean nullable,
                              boolean autoIncrement) {
    }

    private record ColumnInfo(String dataType, Long length, Long precision, boolean nullable,
                              String extra, String collation) {
    }

    private static final class MigrationFailure extends RuntimeException {
        private MigrationFailure(String message) {
            super(message);
        }

        private MigrationFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class UsageException extends RuntimeException {
        private UsageException(String message) {
            super(message);
        }
    }
}
