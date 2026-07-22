package com.dugnan.moqi.chapter.stream;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 配置有界的章节 AI 任务执行线程池。
 */
@Configuration
public class ChapterAiTaskExecutionConfiguration {

    @Bean("chapterAiTaskExecutor")
    public ThreadPoolTaskExecutor chapterAiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("chapter-ai-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
