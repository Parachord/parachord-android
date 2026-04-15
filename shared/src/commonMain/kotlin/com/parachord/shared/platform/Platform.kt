package com.parachord.shared.platform

/**
 * Platform abstractions for code shared between Android and iOS.
 */

/** Platform-agnostic logging. */
expect object Log {
    fun d(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun w(tag: String, msg: String, throwable: Throwable?)
    fun e(tag: String, msg: String)
    fun e(tag: String, msg: String, throwable: Throwable?)
    fun i(tag: String, msg: String)
}

/** Generate a random UUID string. */
expect fun randomUUID(): String

/** Current time in milliseconds since epoch. */
expect fun currentTimeMillis(): Long
