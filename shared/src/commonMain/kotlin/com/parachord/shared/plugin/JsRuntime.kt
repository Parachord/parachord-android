package com.parachord.shared.plugin

import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-agnostic JavaScript execution interface.
 *
 * Abstracts the JS runtime so the plugin system (PluginManager, PluginSyncService)
 * doesn't depend on Android-specific WebView APIs.
 *
 * - **Android**: Implemented by JsBridge (headless WebView)
 * - **iOS**: Implemented via JavaScriptCore (built into iOS, no WebView needed)
 *
 * All plugin code (.axe files) executes through this interface. The implementation
 * is responsible for polyfilling fetch, storage, and console for the JS environment.
 */
interface JsRuntime {

    /** True when the runtime is initialized and ready to execute scripts. */
    val ready: StateFlow<Boolean>

    /** Initialize the JS runtime and load core modules (resolver-loader.js, polyfills). */
    suspend fun initialize()

    /**
     * Evaluate a JavaScript expression and return the result as a string.
     * The expression can be async (wrapped in an IIFE that returns a Promise).
     * Returns null if the runtime is not initialized or evaluation fails.
     */
    suspend fun evaluate(script: String): String?

    /** Tear down the runtime and release resources. */
    fun teardown()
}
