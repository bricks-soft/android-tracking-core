package com.brickssoft.tracking.httpqueue

/** TTL is anchored to capture time; retry and re-enqueue never extend it. */
data class RetentionPolicy(val maxDaysToPersist: Int = 1, val maxRecordsToPersist: Int = -1) {
    init { require(maxDaysToPersist >= 0 && maxRecordsToPersist >= -1) }
    internal fun expiresAt(capturedAt: Long): Long =
        addWithoutOverflow(capturedAt, maxDaysToPersist.toLong() * 86_400_000L)
}

internal fun addWithoutOverflow(value: Long, increment: Long): Long {
    require(value >= 0 && increment >= 0 && value <= Long.MAX_VALUE - increment) { "Timestamp or revision overflow" }
    return value + increment
}
