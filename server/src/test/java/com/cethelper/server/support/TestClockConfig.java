package com.cethelper.server.support;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 用 {@link MutableClock} 覆盖生产环境的系统时钟。
 *
 * <p>用例里 {@code @Import(TestClockConfig.class)} 后注入 {@link MutableClock}，
 * 调用 {@code clock.advance(Duration)} 即可让已签发的凭证过期。</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.now());
    }
}
