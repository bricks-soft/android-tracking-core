package com.brickssoft.tracking.httpqueue

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Chosen queue policy, not a claim about another SDK's retry algorithm. */
data class RetryPolicy(val initialMillis: Long = 5_000, val capMillis: Long = 900_000) {
    init { require(initialMillis > 0 && capMillis >= initialMillis) }

    fun delayMillis(attempt: Int, retryAfter: String?, now: Long, random: RandomSource): Long {
        var base = initialMillis
        repeat((attempt - 1).coerceIn(0, 63)) { base = if (base > capMillis / 2) capMillis else base * 2 }
        val jitter = random.nextDouble().also { require(it >= 0 && it < 1) }
        val delay = (base.coerceAtMost(capMillis) * (1 + jitter * 0.2)).toLong().coerceAtMost(capMillis)
        return maxOf(delay, retryAfterMillis(retryAfter, now)).coerceAtMost(capMillis)
    }

    private fun retryAfterMillis(value: String?, now: Long): Long {
        val text = value?.trim() ?: return 0
        if (text.matches(Regex("[0-9]+"))) {
            val seconds = text.toLongOrNull() ?: return capMillis
            return if (seconds >= capMillis / 1000 + 1) capMillis else (seconds * 1000).coerceAtMost(capMillis)
        }
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
            isLenient = false
        }
        return try { ((date.parse(text)?.time ?: now) - now).coerceIn(0, capMillis) }
        catch (_: java.text.ParseException) { 0 }
    }
}
