package com.taskflow.core

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Abstraction over the system clock so business rules are deterministic in tests. */
interface TimeProvider {
    fun now(): Instant
    fun zone(): ZoneId
    fun today(): LocalDate = LocalDate.ofInstant(now(), zone())
    fun nowDateTime(): LocalDateTime = LocalDateTime.ofInstant(now(), zone())
}

@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {
    private val clock: Clock = Clock.systemDefaultZone()
    override fun now(): Instant = clock.instant()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

class FixedTimeProvider(var current: LocalDateTime, private val zoneId: ZoneId = ZoneId.of("UTC")) : TimeProvider {
    override fun now(): Instant = current.atZone(zoneId).toInstant()
    override fun zone(): ZoneId = zoneId
}
