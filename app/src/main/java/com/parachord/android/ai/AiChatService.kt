@file:Suppress("unused")
package com.parachord.android.ai

/**
 * Source-compat typealias. The real implementation moved to
 * `com.parachord.shared.ai.AiChatService`. The Android-only `DjToolExecutor`
 * (which dispatches to Parachord MCP / playback actions) is forwarded into
 * the shared service via an `executeTool` suspend lambda wired in
 * `AndroidModule`.
 */
typealias AiChatService = com.parachord.shared.ai.AiChatService
