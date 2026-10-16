package com.somphie.floor.api

import com.somphie.floor.application.GuestDataRetentionService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class GuestDataRetentionJob(
    private val guestDataRetentionService: GuestDataRetentionService,
) {
    @Scheduled(fixedDelayString = "\${somphie.floor.retention-interval-ms:86400000}")
    fun runScheduled() {
        guestDataRetentionService.applyRetention()
    }
}
