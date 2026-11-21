package com.cethelper.server.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 严格 JSON 类型校验：请求体里的字符串字段只接受 JSON 字符串。
 *
 * <p>Jackson 默认会把数字、布尔值"宽容地"转成字符串，导致 {@code {"password": 12345678}}
 * 被当成口令 {@code "12345678"} 处理，掩盖客户端的类型错误。这里关掉该隐式转换，
 * 使这类请求在反序列化阶段失败并统一返回 400 {@code INVALID_INPUT}；
 * 真正以字符串形式提交的 {@code "12345678"} 不受影响。</p>
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictTextualCoercionCustomizer() {
        return builder -> builder.postConfigurer(mapper -> mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
