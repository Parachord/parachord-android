package com.parachord.shared.network

import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-agnostic network connectivity monitor.
 *
 * - **Android**: wraps ConnectivityManager with NetworkCallback
 * - **iOS**: wraps NWPathMonitor
 */
interface NetworkMonitor {
    /** Reactive connectivity state. True when the device has internet access. */
    val isOnline: StateFlow<Boolean>
}
