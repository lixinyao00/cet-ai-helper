package com.cethelper.server.tutor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** AI 助教流式推送专用线程池：每个请求一个虚拟线程，应用关闭时自动停止。 */
@Configuration(proxyBeanMethods = false)
public class TutorConfig {

    @Bean(name = "tutorStreamExecutor", destroyMethod = "shutdown")
    public ExecutorService tutorStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
