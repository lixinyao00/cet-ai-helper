package com.cethelper.server.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 基础 Bean：可替换的时钟与 Spring Security 提供的 BCrypt 编码器。 */
@Configuration(proxyBeanMethods = false)
public class AppConfig {

    /** 统一通过该时钟取当前时间，测试可替换为可拨动的时钟以验证凭证过期。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
