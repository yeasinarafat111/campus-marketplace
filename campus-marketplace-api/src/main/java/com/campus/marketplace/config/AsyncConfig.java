package com.campus.marketplace.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Runs notification emails off the request thread. With {@code app.notifications.async=false} (the test
 * setting) they run inline, so tests can assert on them without waiting.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "notificationExecutor")
    public TaskExecutor notificationExecutor(@Value("${app.notifications.async:true}") boolean async) {
        if (!async) {
            return new SyncTaskExecutor();
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("notify-");
        return executor;
    }
}
