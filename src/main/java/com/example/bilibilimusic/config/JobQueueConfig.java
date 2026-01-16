package com.example.bilibilimusic.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class JobQueueConfig {

    @Bean(name = "jobQueueExecutor")
    public Executor jobQueueExecutor(@Value("${job.queue.worker.concurrency:2}") int concurrency) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, concurrency));
        executor.setMaxPoolSize(Math.max(1, concurrency));
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("job-queue-");
        executor.initialize();
        return executor;
    }
}

