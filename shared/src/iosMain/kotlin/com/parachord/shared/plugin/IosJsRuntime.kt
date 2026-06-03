package com.parachord.shared.plugin

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.JavaScriptCore.JSContext
import platform.JavaScriptCore.JSValue

/**
 * iOS [JsRuntime] implementation backed by JavaScriptCore (JSC).
 *
 * Where Android needs a headless WebView to host JS (because Android's
 * standalone JSC binding isn't available without one), iOS exposes JSC
 * directly via the `JavaScriptCore` framework — no WebView, no
 * `WebViewClient.onPageFinished` plumbing, no JS<->native bridging via
 * `@JavascriptInterface`. A `JSContext` IS the runtime.
 *
 * ## Scope: phase 4.1 — JSContext stand-up + evaluate only
 *
 * This class supports synchronous JS evaluation (the
 * `(async () => { ... })()` await-string pattern from the Android
 * `evaluate` works too because JSC returns the Promise's resolution
 * to the caller). The bigger pieces of the .axe plugin host —
 * `fetch` / `console` / `storage` / MBID-resolution polyfills that
 * Android's `NativeBridge` provides — need to inject Kotlin-side
 * functions as JS callables, and Kotlin/Native's JSC bindings DON'T
 * expose the `JSContext.setObject(_:forKeyedSubscript:)` or
 * `JSValue.setValue(_:forProperty:)` selectors that JSC's
 * `JSObjectKeyedSubscript` informal protocol provides. That binding
 * gap is the reason the polyfills aren't here yet.
 *
 * ## Path forward for the polyfills
 *
 * Two options when phase 4.2 lands:
 *   1. **Swift companion.** Subclass / wrap this from Swift in
 *      `iosApp/`, where JSC's `[ctx setObject:forKeyedSubscript:]`
 *      is fully ergonomic (`ctx["__nativeLog"] = { level, msg in ... }`).
 *      Inject the Swift wrapper through Koin via a factory closure.
 *   2. **C-API interop.** Drop to `JSObjectSetProperty` from
 *      `JavaScriptCore/JSObjectRef.h` via raw `kotlinx.cinterop`
 *      pointers. Doable but ugly — every callback needs an explicit
 *      `JSObjectCallAsFunctionCallback` thunk.
 *
 * Option 1 wins on readability and matches the spirit of the
 * "iOS-side Kotlin where it's clean, Swift where it's not" split
 * already used elsewhere in iosMain.
 */
@OptIn(ExperimentalForeignApi::class)
class IosJsRuntime : JsRuntime {

    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private var context: JSContext? = null

    override suspend fun initialize() {
        if (context != null) return
        val ctx = JSContext()
        // Surface uncaught JS exceptions instead of letting JSC swallow
        // them silently — without an exception handler `evaluate("throw 1")`
        // returns null with no signal as to why.
        ctx.setExceptionHandler { _, exception ->
            val message = exception?.toString() ?: "<unknown JS error>"
            println("[IosJsRuntime] uncaught JS exception: $message")
        }
        context = ctx
        _ready.value = true
    }

    override suspend fun evaluate(script: String): String? {
        val ctx = context ?: return null
        val result: JSValue? = ctx.evaluateScript(script)
        if (result == null || result.isUndefined || result.isNull) return null
        return result.toString()
    }

    override fun teardown() {
        context = null
        _ready.value = false
    }
}
