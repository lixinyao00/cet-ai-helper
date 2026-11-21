package com.cethelper.server.auth;

import com.cethelper.server.common.ApiException;
import com.cethelper.server.config.CetProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号与会话的核心逻辑。
 *
 * <p>用户名唯一性依赖数据库唯一约束；会话凭证使用 {@link SecureRandom} 生成的 256 位不透明随机串，
 * 库中只保存其 SHA-256 摘要与过期时间。</p>
 */
@Service
public class AuthService {

    /** 3–32 位，字母开头，仅字母、数字、下划线。 */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{2,31}$");
    private static final int PASSWORD_MIN_LENGTH = 8;
    private static final int PASSWORD_MAX_LENGTH = 72;
    /** BCrypt 只取前 72 字节，超过则直接拒绝，避免"看似不同实则等价"的口令。 */
    private static final int PASSWORD_MAX_BYTES = 72;
    private static final int TOKEN_BYTES = 32;
    /** 用户名不存在与密码错误必须返回完全相同的响应，避免账号枚举。 */
    private static final String INVALID_CREDENTIALS_MESSAGE = "用户名或密码不正确。";

    private final StudentRepository students;
    private final AuthSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final Duration tokenTtl;
    private final SecureRandom secureRandom = new SecureRandom();
    /** 用户不存在时也做一次 BCrypt 比对，拉平响应时间。 */
    private final String timingDummyHash;

    public AuthService(StudentRepository students,
                       AuthSessionRepository sessions,
                       PasswordEncoder passwordEncoder,
                       Clock clock,
                       CetProperties properties) {
        this.students = students;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.tokenTtl = properties.getAuth().getTokenTtl();
        this.timingDummyHash = passwordEncoder.encode("timing-equalizer-not-a-real-password");
    }

    /** 注册学生账号；用户名冲突统一返回 409。 */
    @Transactional
    public Student register(String username, String password) {
        validateUsername(username);
        validatePassword(password);
        String passwordHash = passwordEncoder.encode(password);
        try {
            // flush 让唯一约束在事务内立刻生效，并发注册时另一个请求会拿到 DataIntegrityViolationException。
            return students.saveAndFlush(new Student(username, passwordHash, Instant.now(clock)));
        } catch (DataIntegrityViolationException e) {
            throw usernameTaken();
        }
    }

    /** 校验用户名口令并签发会话凭证。 */
    @Transactional
    public IssuedToken login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw ApiException.invalidInput("请输入用户名和密码。");
        }
        // 超过 BCrypt 72 字节上限的口令不可能与任何已存哈希匹配，直接按凭据错误处理，
        // 避免 BCrypt 截断后"看似不同实则等价"。
        if (password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
            throw invalidCredentials();
        }
        Student student = students.findByUsername(username).orElse(null);
        if (student == null) {
            passwordEncoder.matches(password, timingDummyHash);
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(password, student.getPasswordHash())) {
            throw invalidCredentials();
        }

        byte[] raw = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant issuedAt = Instant.now(clock);
        Instant expiresAt = issuedAt.plus(tokenTtl);

        sessions.save(new AuthSession(student.getId(), TokenDigest.sha256Hex(token), issuedAt, expiresAt));
        return new IssuedToken(token, expiresAt);
    }

    /** 按 Bearer 凭证识别当前学生：未撤销且未过期才有效。 */
    @Transactional(readOnly = true)
    public Optional<AuthenticatedStudent> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        AuthSession session = sessions.findByTokenDigest(TokenDigest.sha256Hex(rawToken)).orElse(null);
        if (session == null || !session.isActive(now)) {
            return Optional.empty();
        }
        return students.findById(session.getStudentId())
                .map(student -> new AuthenticatedStudent(student.getId(), student.getUsername(), session.getId()));
    }

    /** 撤销当前会话，退出后同一凭证立即失效。 */
    @Transactional
    public void logout(Long sessionId) {
        sessions.revoke(sessionId, Instant.now(clock));
    }

    /** 用户名规则：3–32 位，字母开头，仅字母、数字、下划线。 */
    public static void validateUsername(String username) {
        if (username == null || username.isBlank()) {
            throw ApiException.invalidInput("请输入用户名。");
        }
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw ApiException.invalidInput("用户名需为 3–32 位，以字母开头，仅可包含字母、数字或下划线。");
        }
    }

    /** 密码规则：8–72 个字符，UTF-8 不超过 72 字节，首尾不得有空白。 */
    public static void validatePassword(String password) {
        if (password == null || password.isEmpty()) {
            throw ApiException.invalidInput("请输入密码。");
        }
        if (password.length() < PASSWORD_MIN_LENGTH || password.length() > PASSWORD_MAX_LENGTH) {
            throw ApiException.invalidInput("密码长度需为 8–72 个字符。");
        }
        if (!password.equals(password.strip())) {
            throw ApiException.invalidInput("密码首尾不能包含空白字符。");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
            throw ApiException.invalidInput("密码过长：UTF-8 编码后不能超过 72 字节。");
        }
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", INVALID_CREDENTIALS_MESSAGE);
    }

    private static ApiException usernameTaken() {
        return new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "该用户名已被注册，请换一个。");
    }

    /** 新签发的凭证；expiresAt 截断到秒，便于客户端与文档展示。 */
    public record IssuedToken(String accessToken, Instant expiresAt) {

        public String expiresAtIso() {
            return expiresAt.truncatedTo(ChronoUnit.SECONDS).toString();
        }
    }
}
