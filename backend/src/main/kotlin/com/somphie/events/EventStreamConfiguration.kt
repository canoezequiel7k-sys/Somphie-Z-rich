package com.somphie.events

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ScheduledThreadPoolExecutor

@Configuration
class EventStreamConfiguration {
    @Bean(destroyMethod = "shutdown")
    fun eventStreamScheduler(): ScheduledThreadPoolExecutor =
        ScheduledThreadPoolExecutor(4) { task ->
            Thread(task, "somphie-event-stream").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
}
