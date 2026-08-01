package com.dugnan.moqi.agent;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 配置 Agent Runtime 专属的有界执行器。
 *
 * @author dgn
 * @date 2026-08-01
 * @description 配置 Agent Runtime 专属有界执行器和调度能力。
 */
@Configuration
@EnableScheduling
public class AgentRuntimeExecutionConfiguration {

    @Bean("agentRuntimeExecutor")
    public ThreadPoolTaskExecutor agentRuntimeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("agent-runtime-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
