package com.cethelper.server.common;

import com.cethelper.server.config.CetProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务健康与运行模式探测。
 *
 * <p>客户端在设置服务器地址时可调用该接口「测试连接」，并据此显示当前连接的是
 * demo 演示模式还是 live 真实模型。接口<b>不需要</b> Bearer 凭证（见 {@code SecurityConfig}）。</p>
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    /** 服务接口版本号。 */
    static final String VERSION = "0.2.0";

    private final CetProperties properties;

    public HealthController(CetProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", properties.getExplanation().isLive() ? "live" : "demo", VERSION);
    }

    /**
     * @param status  固定 {@code UP}，表示服务可用
     * @param mode    {@code demo} 或 {@code live}，与释义/批改接口当前使用的模式一致
     * @param version 服务版本号
     */
    public record HealthResponse(String status, String mode, String version) {
    }
}
