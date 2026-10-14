package com.somphie.floor.api

import com.somphie.floor.application.WaiterCallService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class ExpireWaiterCallsJob(
    private val waiterCallService: WaiterCallService,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${somphie.floor.expiry-interval-ms:60000}")
    fun runScheduled() {
        waiterCallService.expireCallsAt(clock.instant())
    }
}
