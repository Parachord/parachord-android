# Shuffleupagus AI Chat — Design Document

## Overview

Add the Shuffleupagus conversational DJ feature to the Android app, matching the desktop app's AI chat system. Three AI providers (ChatGPT, Claude, Gemini) can control playback, search for music, manage the queue, create playlists, and toggle shuffle via 8 DJ tools. Users configure API keys in Settings → Plug-Ins, then chat via a full-screen chat route accessed from the existing "+" action overlay.

## Architecture

Four layers mirroring the desktop's `ai-chat.js`, `dj-tools.js`, `ai-chat-integration.js`, and `.axe` plugin structure:

1. **AI Provider Layer** — Three native Kotlin provider implementations (no `.axe` files)
2. **DJ Tools Layer** — 8 tool definitions with JSON Schema parameters and executors
3. **Chat Service Layer** — Conversation orchestrator with history, context injection, tool loop
4. **UI Layer** — Full-screen ChatScreen with provider selector, message list, text input

## AI Providers

### Common Interface

```kotlin
interface AiChatProvider {
    val id: String
    val name: String
    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<DjToolDefinition>,
        config: AiProviderConfig,
    ): AiChatResponse
}
```

### ChatGPT
- Endpoint: `https://api.openai.com/v1/chat/completions`
- Auth: `Authorization: Bearer {apiKey}`
- Tool format: OpenAI function calling (`type: "function"`)
- Models: gpt-4o-mini (default), gpt-4o, gpt-4-turbo, gpt-3.5-turbo
- API key link: `https://platform.openai.com/api-keys`

### Claude
- Endpoint: `https://api.anthropic.com/v1/messages`
- Auth: `x-api-key` header + `anthropic-version: 2023-06-01`
- Tool format: `tool_use` content blocks with `input_schema`
- System prompt as top-level `system` field
- Tool results as `{ role: "user", content: [{ type: "tool_result" }] }`
- Models: claude-sonnet-4-20250514 (default), claude-3-5-sonnet, claude-3-5-haiku
- API key link: `https://console.anthropic.com/settings/keys`

### Google Gemini
- Endpoint: `https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={apiKey}`
- Auth: API key in URL query parameter
- Tool format: `functionDeclarations`
- System prompt as `system_instruction`
- Roles: `user`/`model` (not `assistant`)
- Models: gemini-2.0-flash (default), gemini-1.5-pro, gemini-1.5-flash
- API key link: `https://aistudio.google.com/apikey`

### Error Handling (all providers)
- 401 → "Invalid API key. Please check your settings."
- 429 → "Rate limit reached. Please try again in a moment."
- Connection failure → "Couldn't connect to the AI service."
- 404 → "The AI model wasn't found."

## DJ Tools

Eight tools matching the desktop's `dj-tools.js`:

| Tool | Description | Key Parameters |
|------|-------------|----------------|
| `play` | Play a track (clears queue first) | artist, title |
| `control` | Pause/resume/skip/previous | action (enum) |
| `search` | Search across resolvers | query, limit |
| `queue_add` | Add tracks to queue | tracks[], position, playFirst |
| `queue_clear` | Clear the queue | (none) |
| `create_playlist` | Create playlist with tracks | name, tracks[] |
| `shuffle` | Toggle shuffle mode | enabled (boolean) |
| `block_recommendation` | Block from recommendations | type, name/title/artist |

### Tool Executor Dependencies
- `PlaybackController` — play, control, queue, shuffle
- `ResolverManager` — search
- `PlaylistRepository` — create_playlist
- `PlaybackStateHolder` — read current state
- `SettingsStore` — block_recommendation persistence

## Chat Service

Mirrors `ai-chat.js` behavior:

- **System prompt**: Includes current date, now playing, queue (first 20 tracks), playback state, shuffle mode, listening history (top 5 artists), recommendation blocklist
- **History management**: Max 50 messages, trims keeping first + last 49
- **Tool call loop**: Execute tools → add results as tool messages → send follow-up → repeat up to 5 iterations
- **Per-provider history**: Separate conversation per provider, persisted to DataStore as JSON

### Message Format
```kotlin
data class ChatMessage(
    val role: ChatRole,              // USER, ASSISTANT, SYSTEM, TOOL
    val content: String,
    val toolCalls: List<ToolCall>?,
    val toolCallId: String?,
)
```

## Chat Screen UI

Full-screen composable at `Routes.CHAT`:

- **Top bar**: Back arrow, "Shuffleupagus" title + mammoth icon, provider dropdown (right)
- **Empty state**: Mammoth icon centered with "Ask me to play something, build a playlist, or control your music."
- **User messages**: Right-aligned, purple bubble, white text
- **Assistant messages**: Left-aligned, surface-colored bubble, small mammoth icon
- **Loading**: Three animated bouncing dots + progress text ("Searching...", "Adding to queue...")
- **Input**: Rounded OutlinedTextField with "Ask your DJ..." placeholder, circular purple send button
- **No providers configured**: Prompt directing to Settings → Plug-Ins

## Settings Integration

Three new META_SERVICE plugins in `builtInPlugins`:

| Plugin | ID | Color | Capabilities |
|--------|-----|-------|-------------|
| ChatGPT | `chatgpt` | `#10a37f` | AI DJ, Chat |
| Claude | `claude` | `#D97757` | AI DJ, Chat |
| Google Gemini | `gemini` | `#4285f4` | AI DJ, Chat |

Each config sheet:
- Clickable link to get API key (platform.openai.com, console.anthropic.com, ai.google.dev)
- Password-style API key input field
- Model dropdown selector
- Connected indicator (green checkmark)
- Clear button

### New SettingsStore Keys
- `chatgpt_api_key`, `chatgpt_model`
- `claude_api_key`, `claude_model`
- `gemini_api_key`, `gemini_model`

## File Structure

### New Files
```
ai/
├── AiChatProvider.kt              # Interface + data classes
├── AiChatService.kt               # Conversation orchestrator
├── ChatContextProvider.kt         # App state for system prompt
├── providers/
│   ├── ChatGptProvider.kt
│   ├── ClaudeProvider.kt
│   └── GeminiProvider.kt
└── tools/
    ├── DjToolDefinitions.kt       # 8 tool schemas
    └── DjToolExecutor.kt          # Tool execution
ui/screens/chat/
├── ChatScreen.kt                  # Full-screen composable
└── ChatViewModel.kt               # ViewModel
```

### Modified Files
- `SettingsStore.kt` — 6 new preference keys
- `SettingsViewModel.kt` — connected states + save/clear for 3 providers
- `SettingsScreen.kt` — 3 new plugins, config sheets with API key + model + link
- `Navigation.kt` — `Routes.CHAT` route
- `MainActivity.kt` — wire `onChatWithShuffleupagus` to navigate
- `di/AppModule.kt` — Hilt providers for AI components
