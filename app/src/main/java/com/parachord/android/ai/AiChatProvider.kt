@file:Suppress("unused")
package com.parachord.android.ai

/**
 * Re-exports from shared module for backward compatibility.
 * Existing app code can continue importing from com.parachord.android.ai.
 */
typealias ChatRole = com.parachord.shared.ai.ChatRole
typealias ChatMessage = com.parachord.shared.ai.ChatMessage
typealias ToolCall = com.parachord.shared.ai.ToolCall
typealias AiChatResponse = com.parachord.shared.ai.AiChatResponse
typealias AiProviderConfig = com.parachord.shared.ai.AiProviderConfig
typealias AiProviderInfo = com.parachord.shared.ai.AiProviderInfo
typealias AiChatProvider = com.parachord.shared.ai.AiChatProvider
typealias DjToolDefinition = com.parachord.shared.ai.DjToolDefinition
