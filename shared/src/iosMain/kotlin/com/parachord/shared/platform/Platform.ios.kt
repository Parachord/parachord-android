package com.parachord.shared.platform

import platform.Foundation.NSDate
import platform.Foundation.NSUUID
import platform.Foundation.NSLog
import platform.Foundation.timeIntervalSince1970

actual object Log {
    actual fun d(tag: String, msg: String) { NSLog("D/$tag: $msg") }
    actual fun d(tag: String, msg: String, throwable: Throwable?) { NSLog("D/$tag: $msg ${throwable?.message ?: ""}") }
    actual fun i(tag: String, msg: String) { NSLog("I/$tag: $msg") }
    actual fun i(tag: String, msg: String, throwable: Throwable?) { NSLog("I/$tag: $msg ${throwable?.message ?: ""}") }
    actual fun w(tag: String, msg: String) { NSLog("W/$tag: $msg") }
    actual fun w(tag: String, msg: String, throwable: Throwable?) { NSLog("W/$tag: $msg ${throwable?.message ?: ""}") }
    actual fun e(tag: String, msg: String) { NSLog("E/$tag: $msg") }
    actual fun e(tag: String, msg: String, throwable: Throwable?) { NSLog("E/$tag: $msg ${throwable?.message ?: ""}") }
}

actual fun randomUUID(): String = NSUUID().UUIDString

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
