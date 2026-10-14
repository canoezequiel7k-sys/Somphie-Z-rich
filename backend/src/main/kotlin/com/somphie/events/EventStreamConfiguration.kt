package com.somphie.events

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

@Configuration
class EventStreamConfiguration {
    @Bean(destroyMethod = "shutdown")
    fun eventStreamScheduler(): ScheduledExecutorService =
        Executors.newScheduledThreadPool(4) { task ->
            Thread(task, "somphie-event-stream").apply { isDaemon = true }
        }
}
