package com.cethelper.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 四六级 AI 学习助手后端入口：学生账号 + 会话凭证 + 语境释义。
 *
 * <p>排除 {@link UserDetailsServiceAutoConfiguration}：本服务只用 Bearer 不透明凭证
 * （{@code TokenAuthenticationFilter}），不需要 Spring Security 的默认内存用户，
 * 也就不会在启动时生成并打印默认密码。</p>
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class CetServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CetServerApplication.class, args);
    }
}
