package com.somphie.events

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RestController
@RequestMapping("/events")
class EventStreamController(
    private val eventStreamService: EventStreamService,
    private val scheduler: ScheduledExecutorService,
    private val clock: Clock,
) {
    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @RequestParam scope: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("Last-Event-ID", required = false) lastEventId: String?,
        @AuthenticationPrincipal jwt: Jwt?,
    ): ResponseEntity<SseEmitter> {
        val cursor = parseCursor(lastEventId)
        val access = eventStreamService.authorize(scope, authorization, jwt)
        eventStreamService.ensureCursor(access, cursor)

        val emitter = SseEmitter(0L)
        val future = AtomicReference<ScheduledFuture<*>?>()
        val cursorState = AtomicLong(cursor)
        val lastHeartbeat = AtomicReference(clock.instant())
        val poll = scheduler.scheduleWithFixedDelay(
            {
                try {
                    val refreshedAccess = eventStreamService.authorize(scope, authorization, jwt)
                    eventStreamService.ensureCursor(refreshedAccess, cursorState.get())
                    val events = eventStreamService.eventsAfter(refreshedAccess, cursorState.get())
                    events.forEach { event ->
                        emitter.send(
                            SseEmitter.event()
                                .id(event.eventId.toString())
                                .name(event.type.wireValue)
                                .data(eventEnvelope(event)),
                        )
                        cursorState.set(event.eventId)
                    }
                    if (events.isEmpty() &&
                        Duration.between(lastHeartbeat.get(), clock.instant()) >= HEARTBEAT_INTERVAL
                    ) {
                        emitter.send(SseEmitter.event().comment("heartbeat"))
                        lastHeartbeat.set(clock.instant())
                    }
                } catch (exception: DomainException) {
                    logger.info("Closing event stream after authorization or cursor failure: {}", exception.code)
                    emitter.complete()
                    future.get()?.cancel(false)
                } catch (exception: Exception) {
                    logger.error("Event stream delivery failed", exception)
                    emitter.completeWithError(exception)
                    future.get()?.cancel(false)
                }
            },
            POLL_INTERVAL.toMillis(),
            POLL_INTERVAL.toMillis(),
            TimeUnit.MILLISECONDS,
        )
        future.set(poll)
        emitter.onCompletion { poll.cancel(false) }
        emitter.onTimeout { poll.cancel(false) }
        emitter.onError { poll.cancel(false) }

        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .cacheControl(CacheControl.noCache())
            .header("X-Accel-Buffering", "no")
            .body(emitter)
    }

    private fun eventEnvelope(event: StoredDomainEvent): Map<String, Any?> = linkedMapOf(
        "eventId" to event.eventId,
        "type" to event.type.wireValue,
        "aggregateType" to event.aggregateType.name,
        "aggregateId" to event.aggregateId,
        "version" to event.version,
        "sessionId" to event.sessionId,
        "occurredAt" to event.occurredAt.toString(),
    )

    private fun parseCursor(value: String?): Long {
        if (value == null) return 0
        return value.toLongOrNull()
            ?.takeIf { it >= 0 }
            ?: throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid Last-Event-ID")
    }

    private companion object {
        val logger = LoggerFactory.getLogger(EventStreamController::class.java)
        val HEARTBEAT_INTERVAL: Duration = Duration.ofSeconds(25)
        val POLL_INTERVAL: Duration = Duration.ofSeconds(1)
    }
}
