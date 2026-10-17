package com.somphie.shared.idempotency

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class IdempotencyRetentionJob(
    private val idempotencyRetentionService: IdempotencyRetentionService,
) {
    @Scheduled(fixedDelayString = "\${somphie.idempotency.retention-interval-ms:86400000}")
    fun runScheduled() {
        idempotencyRetentionService.deleteExpiredRecords()
    }
}
