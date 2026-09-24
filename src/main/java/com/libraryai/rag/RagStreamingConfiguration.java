package com.libraryai.rag;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Bounded worker pool for blocking embedding, database, and Gemini calls. */
@Configuration(proxyBeanMethods = false)
class RagStreamingConfiguration {

    @Bean(name = "ragTaskExecutor")
    ThreadPoolTaskExecutor ragTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("rag-stream-");
        executor.initialize();
        return executor;
    }
}
