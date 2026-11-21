package com.cethelper.server.auth;

import com.cethelper.server.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 学生账号与会话接口。 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 注册：成功 201，用户名重复 409，输入不合法 400。 */
    @PostMapping("/auth/register")
    public ResponseEntity<StudentResponse> register(@RequestBody(required = false) RegisterRequest request) {
        Student student = authService.register(
                request == null ? null : request.username(),
                request == null ? null : request.password());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new StudentResponse(student.getId(), student.getUsername()));
    }

    /** 登录：成功 200 并下发不透明凭证，凭据错误统一 401。 */
    @PostMapping("/auth/login")
    public LoginResponse login(@RequestBody(required = false) LoginRequest request) {
        AuthService.IssuedToken issued = authService.login(
                request == null ? null : request.username(),
                request == null ? null : request.password());
        return new LoginResponse(issued.accessToken(), "Bearer", issued.expiresAtIso());
    }

    /** 当前学生身份，完全取自 Bearer 凭证。 */
    @GetMapping("/me")
    public StudentResponse me(Authentication authentication) {
        AuthenticatedStudent student = currentStudent(authentication);
        return new StudentResponse(student.studentId(), student.username());
    }

    /** 退出：撤销当前凭证并返回 204。 */
    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(Authentication authentication) {
        authService.logout(currentStudent(authentication).sessionId());
        return ResponseEntity.noContent().build();
    }

    private static AuthenticatedStudent currentStudent(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedStudent student)) {
            throw ApiException.unauthorized();
        }
        return student;
    }
}
