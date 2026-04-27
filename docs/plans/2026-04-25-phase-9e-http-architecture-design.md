# Phase 9E — Unified HTTP Architecture (Retrofit → Ktor + NativeBridge Convergence)

> **Status:** DESIGN. Brainstormed and validated section-by-section. Awaiting expansion into a TDD-shaped execution plan via `superpowers:writing-plans`.
>
> **For Claude:** This is the architectural spec for the final structural KMP migration phase before iOS work begins. It eliminates the "two HTTP code paths" anti-pattern (native Retrofit clients + plugin OkHttpClient) and establishes a single shared `HttpClient` that both native code and the .axe plugin runtime delegate to. The implementation plan should reference this doc as its source of truth and follow the per-API migration order in Section 6.

**Goal:** Eliminate the dual HTTP architecture (Retrofit interfaces in `app/data/api/` for native code + a separate `pluginHttpClient` `OkHttpClient` in `NativeBridge`) and replace it with a single Ktor `HttpClient` per process that both paths delegate to. Cross-platform code sharing for native API clients (Layer 1) and unified HTTP discipline (User-Agent, sanitized logging, OAuth refresh, eventually rate-limit awareness) across native + plugin (Layer 2) — both addressed by the same workstream.

**Scope:** All 9 native API clients (`SpotifyApi`, `LastFmApi`, `ListenBrainzApi`, `MusicBrainzApi`, `AppleMusicApi`, `AppleMusicLibraryApi`, `TicketmasterApi`, `SeatGeekApi`, `GeoLocationService`) move from Retrofit-in-app to Ktor-in-shared. `NativeBridge.fetch` / `fetchAsync` rerouted to delegate to the same shared `HttpClient`. Per-bridge `pluginHttpClient` field deleted. The 19 .axe plugins continue working unchanged — the JS-visible fetch envelope is preserved byte-for-byte.

**Out of scope:** `RateLimitAwarePlugin` (architectural seat reserved, not built). Header parsing improvement in `NativeBridge` (orthogonal quality fix). Phase 9B (`SettingsStore` → `multiplatform-settings`). Phase 8 (Coil 3). Sync extraction (separate workstream, gated on 9E.1.7 + 9E.1.8). All iOS work.

**Tech stack additions:** Ktor 3.1.1 client (already in `libs.versions.toml`), `org.kotlincrypto.hash:md5` (KMP-native MD5 for Last.fm signing). MockEngine for tests (replaces existing MockWebServer where applicable).

---

## Architectural principle: one HTTP transport per process, native + plugins converged

Today the codebase has at minimum three HTTP code paths on Android:

1. **Native Retrofit clients** in `app/data/api/*Api.kt`, each with its own OkHttpClient (some sharing a global one with the User-Agent interceptor, some not).
2. **Direct OkHttp calls** in `ResolverManager` (iTunes, SoundCloud) and a few other places.
3. **Plugin HTTP via `NativeBridge.fetch` / `fetchAsync`**, which has its own `pluginHttpClient` `OkHttpClient` field that does *not* inherit the global User-Agent interceptor.

This divergence is the structural cause of CLAUDE.md mistake #32 ("Don't create per-API OkHttpClients without the User-Agent interceptor"). It is also why coordinated rate-limit state, OAuth refresh consistency, and HTTP-level caching are difficult to add today — there is no single seam to add them at.

The principle: **after 9E, there is exactly one `HttpClient` per process.** Native typed Ktor clients hold references to it. `NativeBridge.fetch` delegates to it. Adding a new API client = registering a new `KtorClient(httpClient)`. Adding a new interceptor = registering it once on the shared client. Two-paths-converge anti-pattern eliminated by construction.

The principle also implies a guardrail: post-9E, `grep -r "OkHttpClient" app/ shared/` should return only Ktor's internal usage (the OkHttp engine that backs Ktor's `HttpClient` on Android). Any direct application-code instantiation of `OkHttpClient` is a regression.

---

## Module layout

### `shared/commonMain/api/` — typed Ktor clients for native code

One Ktor client class per upstream API. Names match the Retrofit interfaces they replace, so call-site refactoring is mechanical:

| Replaces | New |
|---|---|
| `SpotifyApi.kt` | `SpotifyClient.kt` |
| `LastFmApi.kt` | `LastFmClient.kt` |
| `ListenBrainzApi.kt` | `ListenBrainzClient.kt` |
| `MusicBrainzApi.kt` | `MusicBrainzClient.kt` |
| `AppleMusicApi.kt` | `AppleMusicCatalogClient.kt` |
| `AppleMusicLibraryApi.kt` | `AppleMusicLibraryClient.kt` |
| `TicketmasterApi.kt` | `TicketmasterClient.kt` |
| `SeatGeekApi.kt` | `SeatGeekClient.kt` |
| `GeoLocationService.kt` | `GeoLocationClient.kt` |

Per-API serialization models — already partially in `shared/commonMain/api/*Models.kt` from Phase 1; spot-check + fill gaps during cutover.

### `shared/commonMain/api/transport/` — single shared HTTP infrastructure

- `HttpClientFactory.kt` — `expect fun createHttpClient(json: Json, authProvider: AuthTokenProvider, appConfig: AppConfig): HttpClient`. Already exists from Phase 2.
- `UserAgentPlugin.kt` — wraps Ktor's `DefaultRequest` plugin to set `Parachord/<version> (Android|iOS; https://parachord.app)` on every request. Closes CLAUDE.md mistake #32 permanently.
- `OAuthRefreshPlugin.kt` — global Ktor plugin handling 401-driven token refresh + retry for OAuth-protected hosts (Spotify, SoundCloud). Section 4 details.
- `RateLimitAwarePlugin.kt` (post-MVP, architectural seat reserved) — coordinated rate-limit state per host.

### `shared/commonMain/api/auth/` — auth abstractions

```kotlin
interface AuthTokenProvider {
    suspend fun tokenFor(realm: AuthRealm): AuthCredential?
    suspend fun invalidate(realm: AuthRealm)
}

enum class AuthRealm { Spotify, AppleMusicLibrary, ListenBrainz, LastFm, Ticketmaster, SeatGeek, Discogs }

sealed class AuthCredential {
    data class BearerToken(val accessToken: String) : AuthCredential()
    data class BearerWithMUT(val devToken: String, val mut: String) : AuthCredential()
    data class TokenPrefixed(val prefix: String, val token: String) : AuthCredential()
    data class ApiKeyParam(val paramName: String, val value: String) : AuthCredential()
    data class LastFmSigned(val sharedSecret: String, val sessionKey: String?) : AuthCredential()
}

interface OAuthTokenRefresher {
    suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken?
}
```

`AuthTokenProvider.tokenFor()` is *request-time* — pulled before each call to apply the right auth (Section 3). Cheap, no network. `OAuthTokenRefresher.refresh()` is *response-time* — invoked only when a 401 confirms the cached token is dead. Splitting the interfaces lets us keep the request path fast and mock refresh independently in tests.

### `shared/androidMain/api/` — Android engine binding

Android `HttpClient` engine = OkHttp (Phase 2 decision; unchanged). `AndroidNativeBridgeFetchAdapter.kt` — the Android side of 9E.2; replaces the current `NativeBridge.fetch` body with a delegate to the shared `HttpClient`.

### `shared/iosMain/api/` — iOS engine binding (future)

iOS `HttpClient` engine = Darwin (Phase 2 decision; unchanged). `IosNativeBridgeFetchAdapter.kt` — iOS side of 9E.2 once `IosJsRuntime` exists (currently a stub).

### What stays in `:app` (eventually deleted)

- Existing Retrofit interfaces (`app/data/api/*Api.kt`) — kept *temporarily* during migration, deleted phase-by-phase as each Ktor client lands and call sites cut over.
- `SpotifyAuthInterceptor.kt`, `AppleMusicAuthInterceptor.kt` — replaced by per-client auth applier patterns (Section 3); deleted post-migration.
- `app/.../bridge/NativeBridge.kt` — *keeps* its JS-callable interface and the JS polyfill registration (`webView.addJavascriptInterface(...)`); only the HTTP body of `fetch()` / `fetchAsync()` is rewired. The JS contract (browser-fetch shape) is invariant.
- `app/.../bridge/JsBridge.kt` — completely unchanged. WebView lifecycle, plugin loading. Doesn't touch HTTP.

### Migration approach

- **9E.1 — strict parallel-implementation pattern.** Both the Retrofit interface and the Ktor client coexist for each API during cutover. Repository code switches imports one repository at a time. After all consumers cut over, the Retrofit interface is deleted in the same commit. Avoids a single mega-merge; lets us bake each API independently.
- **9E.2 — single targeted rewire.** `NativeBridge.fetch` body changes; the JS-visible contract (`fetch(url, opts)` returns Response-shaped object) is the spec. Verification is "all 19 .axe plugins still resolve / search / call APIs successfully."

---

## `HttpClient` lifecycle, configuration, and plugin install order

### Configuration

```kotlin
// shared/commonMain/api/transport/HttpClientFactory.kt
expect fun createHttpClient(
    json: Json,
    authProvider: AuthTokenProvider,
    appConfig: AppConfig,
): HttpClient

internal fun HttpClientConfig<*>.installSharedPlugins(
    json: Json,
    authProvider: AuthTokenProvider,
    appConfig: AppConfig,
) {
    install(ContentNegotiation) { json(json) }
    install(Logging) {
        logger = KtorLogger
        level = if (appConfig.isDebug) LogLevel.HEADERS else LogLevel.INFO
        sanitizeHeader { it == HttpHeaders.Authorization }  // never log tokens
    }
    install(DefaultRequest) {
        header(HttpHeaders.UserAgent, appConfig.userAgent)
        // "Parachord/0.5.0 (Android; https://parachord.app)" — closes CLAUDE.md #32
    }
    install(OAuthRefreshPlugin) { tokenProvider = authProvider }
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000   // AI endpoints take 30–60s
        connectTimeoutMillis = 15_000
        socketTimeoutMillis  = 30_000
    }
    expectSuccess = false  // call sites map errors per-status
}
```

### Plugin install order rationale

Order matters in Ktor — installs are layered like middleware. The chosen order:

1. **ContentNegotiation first** → subsequent plugins can read/write the JSON body (e.g., OAuth refresh inspecting an error body).
2. **Logging second** → sees request post-content-negotiation, before auth is attached. Combined with `sanitizeHeader { Authorization }`, logs show the full request shape minus tokens.
3. **DefaultRequest before refresh** → User-Agent applies even on auth-failed retries. Missing User-Agent on an OAuth refresh hit could itself 403.
4. **OAuthRefreshPlugin** → handles 401 → refresh → retry for registered realms. Fires after the response comes back, not before send.
5. **HttpTimeout last** → wraps everything. Request body computation, auth lookup, refresh round-trip all share the same timeout budget.

### Lifecycle

- **Created once** during DI startup. Koin `single { createHttpClient(get(), get(), get()) }`.
- **Never explicitly closed** during normal app life — engine connection pool recycled by the runtime.
- **Test injection** — Koin overrides bind a `HttpClient(MockEngine) { installSharedPlugins(...) }` for tests. Same plugin stack runs in tests, only the engine is swapped.

### Engine ownership decision

**Ktor owns its engine entirely** — *not* configured to wrap any existing app `OkHttpClient`. Two reasons:

1. **Coil 3 (KMP Phase 8) uses Ktor natively.** Once we upgrade, Coil picks up our shared `HttpClient` automatically.
2. **No remaining consumer needs raw OkHttp.** `MediaScanner` doesn't do HTTP. `JsBridge` is WebView. `NativeBridge.fetch` is exactly what 9E.2 is converging *into* the shared client.

Verifiable post-merge with `grep -r OkHttpClient app/ shared/` returning only Ktor's internal usage.

### Five behavior properties this enforces

1. **One User-Agent, always.** Closes the entire class of CLAUDE.md mistake #32 forever — including the plugin path post-9E.2.
2. **No accidental token logging.** `sanitizeHeader` is wired centrally; per-API code can't bypass it.
3. **Timeouts are uniform and AI-friendly.** 60s request timeout matches the AI provider reality.
4. **Explicit error handling.** `expectSuccess = false` means call sites pattern-match on `HttpResponse.status`. Maps cleanly to existing `Result<T>` patterns.
5. **Tests run the full plugin stack** with `MockEngine`. Auth pipeline and error mapping are exercised by tests, not just response shapes.

---

## Per-API auth strategy (host-routed via per-client appliers, not a global plugin)

The auth landscape across our 9 APIs uses **6 different schemes**, none of which match Ktor's stock `Auth` plugin (single-scheme, single-realm).

| API | Host(s) | Scheme |
|---|---|---|
| Spotify | `api.spotify.com` + `accounts.spotify.com` | `Authorization: Bearer <access_token>` (OAuth refreshable) |
| Apple Music Catalog | `api.music.apple.com` | `Authorization: Bearer <dev_token>` |
| Apple Music Library | `api.music.apple.com` (path-routed) | `Authorization: Bearer <dev_token>` + `Music-User-Token: <mut>` |
| Last.fm | `ws.audioscrobbler.com` | Per-request MD5 signing: `api_sig` query param |
| ListenBrainz | `api.listenbrainz.org` | `Authorization: Token <user_token>` |
| MusicBrainz | `musicbrainz.org` + `mapper.listenbrainz.org` | None |
| Ticketmaster | `app.ticketmaster.com` | `?apikey=<key>` query param |
| SeatGeek | `api.seatgeek.com` | `?client_id=<id>` query param |
| Discogs (post-9E.2) | `api.discogs.com` | `Authorization: Discogs token=<token>` |

### Why not centralize via a `HostRoutedAuthPlugin`

Initially considered. Killed for three reasons:

1. **Path-routing for AM library/catalog.** Same host, different auth requirements. Forces the plugin into URL pattern matching that's API-specific knowledge leaking into shared infra.
2. **Last.fm signing is fundamentally per-request.** A plugin would need to know how to build the signature from request params — that's API-specific code in shared infra.
3. **NativeBridge.fetch shouldn't auto-inject auth.** Plugins might call third-party APIs we don't have tokens for, and silent token injection is a security surprise. Centralized routing would need an opt-out signal from plugin-originated fetches.

### Per-API auth pattern

Each Ktor client owns its auth, applied per-request via small helpers:

```kotlin
// shared/commonMain/api/SpotifyClient.kt
class SpotifyClient(
    private val http: HttpClient,
    private val tokens: AuthTokenProvider,
) {
    suspend fun search(query: String, types: List<String>): SearchResponse {
        val token = (tokens.tokenFor(AuthRealm.Spotify) as? AuthCredential.BearerToken)?.accessToken
        return http.get("https://api.spotify.com/v1/search") {
            parameter("q", query)
            parameter("type", types.joinToString(","))
            token?.let { bearerAuth(it) }
        }.body()
    }
}

// shared/commonMain/api/AppleMusicLibraryClient.kt
class AppleMusicLibraryClient(
    private val http: HttpClient,
    private val tokens: AuthTokenProvider,
) {
    suspend fun listPlaylists(): AmPlaylistsResponse {
        val cred = tokens.tokenFor(AuthRealm.AppleMusicLibrary) as? AuthCredential.BearerWithMUT
            ?: throw AppleMusicReauthRequiredException("missing MUT")
        return http.get("https://api.music.apple.com/v1/me/library/playlists") {
            bearerAuth(cred.devToken)
            header("Music-User-Token", cred.mut)
        }.body()
    }
}

// shared/commonMain/api/LastFmClient.kt — per-request signing
class LastFmClient(
    private val http: HttpClient,
    private val tokens: AuthTokenProvider,
    private val apiKey: String,
) {
    suspend fun scrobble(track: String, artist: String, timestamp: Long): LastFmResponse {
        val cred = tokens.tokenFor(AuthRealm.LastFm) as? AuthCredential.LastFmSigned
            ?: error("Last.fm not authed")
        val params = sortedMapOf(
            "method" to "track.scrobble",
            "track" to track,
            "artist" to artist,
            "timestamp" to timestamp.toString(),
            "api_key" to apiKey,
            "sk" to (cred.sessionKey ?: error("missing session key")),
        )
        val sig = lastFmSignature(params, cred.sharedSecret)  // private helper using KMP-native MD5
        return http.post("https://ws.audioscrobbler.com/2.0/") {
            params.forEach { (k, v) -> parameter(k, v) }
            parameter("api_sig", sig)
            parameter("format", "json")
        }.body()
    }
}
```

### Five properties this enforces

1. **Auth is grep-able per API.** `bearerAuth(token)` calls live in the API client; no jumping between API code and a shared registry.
2. **Path-routing is implicit and free.** AM Library client knows it needs MUT; AM Catalog client doesn't.
3. **Last.fm signing lives where the params are built.** The signature computation is right next to the request that needs signing.
4. **Plugin fetch (9E.2) gets no auth automatically.** `NativeBridge.fetch` calls go through the bare shared `HttpClient` which has no auth installed. Plugins must explicitly add their own `Authorization` header in JS if needed. This matches current plugin behavior; no breaking change.
5. **`AuthTokenProvider` is the only abstraction across platforms.** Android implementation reads from `SecureTokenStore` + `SettingsStore`; iOS implementation reads from Keychain + `multiplatform-settings` (post-Phase 9B).

### Where `AuthTokenProvider` is implemented

**Phase 9E.1 (now):** lives in `:app` as `AndroidAuthTokenProvider`, backed by existing `SecureTokenStore` + `SettingsStore`. Wired via Koin. The interface is in `commonMain` so iOS can implement it later.

**Post-Phase 9B (later):** moves to `commonMain` once `SettingsStore` is multiplatform-settings-backed. iOS implementation uses Keychain via `expect/actual`.

---

## `OAuthRefreshPlugin` — global refresh-on-401 with single-flight semantics

The *global* Ktor plugin (vs. per-API auth above). Its job: when a registered OAuth realm returns 401, refresh the token once and retry the original request.

### Refreshable realms

| Realm | Refresh endpoint | Notes |
|---|---|---|
| `Spotify` | `accounts.spotify.com/api/token` (grant_type=refresh_token) | 1-hour access token TTL; refresh frequently used |
| `SoundCloud` | SoundCloud OAuth refresh endpoint | Similar pattern |

### Explicitly NOT refreshable on 401

- `AppleMusicLibrary` — MUT failure is a different flow; existing `AppleMusicReauthRequiredException` triggers Apple's MusicKit auth UI, not a token refresh. Per-API code in `AppleMusicLibraryClient` handles AM 401s directly; plugin doesn't intercept.
- `LastFm` — session keys don't expire.
- `ListenBrainz` — user tokens are long-lived; 401 means revoked.
- `Ticketmaster`, `SeatGeek`, `Discogs` — API keys, no refresh concept.

The plugin checks request URL host on intercept; if it's not in the refreshable set, the plugin is invisible.

### Behavior contract

```kotlin
client.plugin(HttpSend).intercept { request ->
    val firstAttempt = execute(request)
    if (firstAttempt.response.status != HttpStatusCode.Unauthorized) return@intercept firstAttempt

    val realm = refreshableHosts[request.url.host] ?: return@intercept firstAttempt
    val originalToken = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")

    val newCredential = singleFlightRefresh(realm, originalToken)
        ?: throw ReauthRequiredException(realm)

    val retryRequest = request.copyWithNewBearer(newCredential.accessToken)
    val secondAttempt = execute(retryRequest)
    if (secondAttempt.response.status == HttpStatusCode.Unauthorized) {
        throw ReauthRequiredException(realm)
    }
    secondAttempt
}
```

### Single-flight + thundering-herd avoidance

```kotlin
private val mutexes = ConcurrentMap<AuthRealm, Mutex>()

private suspend fun singleFlightRefresh(
    realm: AuthRealm,
    originalToken: String?,
): AuthCredential.BearerToken? {
    val mutex = mutexes.computeIfAbsent(realm) { Mutex() }
    return mutex.withLock {
        // Did someone else refresh while we waited?
        val current = tokens.tokenFor(realm) as? AuthCredential.BearerToken
        if (current != null && current.accessToken != originalToken) {
            return@withLock current  // Use their refreshed token; skip our refresh
        }
        refresher.refresh(realm)
    }
}
```

### Three properties this design enforces

1. **Single-flight refresh per realm.** A `Mutex` per realm prevents N concurrent 401s from launching N refresh requests.
2. **Thundering-herd avoidance.** Inside the mutex, before refreshing, the plugin checks whether the *current* token differs from what this request sent. If yes, another request already refreshed during the wait — just retry with the new token, no second refresh.
3. **Two strikes = re-auth required.** If the retry also returns 401, throw `ReauthRequiredException(realm)`. Call sites can catch and prompt re-login.

### What the plugin does NOT do

1. **Doesn't re-apply per-API auth schemes.** Only swaps the `Authorization: Bearer <new>` header. Works for Spotify and SoundCloud (the only OAuth-refreshable realms). AM library uses Bearer + MUT — but AM library isn't refreshable.
2. **Doesn't pre-emptively refresh.** Pre-emptive refresh is an optimization that adds state-tracking complexity without changing correctness.
3. **Doesn't intercept 403.** Spotify uses 403 for "scope insufficient" — that's a real authz failure, not token-expired.

### Tests

`MockEngine` lets us stage 401-then-200 responses to verify:

- Single 401 on Spotify host triggers refresh + retry; final response is 200.
- N concurrent 401s on Spotify trigger one refresh, all N retry with the new token.
- 401 → refresh → 401 throws `ReauthRequiredException(Spotify)`.
- 401 from Last.fm host (not refreshable) propagates as 401, no refresh attempted.
- 401 from AM library host propagates as 401, no refresh attempted; AM client's own 401 handling fires.

---

## 9E.2 — `NativeBridge.fetch` transport convergence

The JS-side contract is simple — the entire envelope passed back to JS today is `{"status": int, "ok": bool, "body": string}`. Plugins consume that shape via the `bootstrap.html` `window.fetch` polyfill. **9E.2 preserves this envelope byte-for-byte.** Only the underlying HTTP transport changes.

### Today: per-bridge `OkHttpClient`, divergent from native API path

```kotlin
// app/.../bridge/NativeBridge.kt — current state
val response = pluginHttpClient.newCall(requestBuilder.build()).execute()
val responseBody = response.body?.string() ?: ""
"""{"status":${response.code},"ok":${response.isSuccessful},"body":${escapeJsonString(responseBody)}}"""
```

`pluginHttpClient` is a separate `OkHttpClient` field on `NativeBridge` that does not inherit the global User-Agent interceptor. It does not coordinate rate limits with native code. It is the canonical example of "two HTTP code paths."

### After 9E.2: delegate to the shared `HttpClient`

```kotlin
class NativeBridge(
    private val webView: WebView?,
    private val scope: CoroutineScope,
    private val httpClient: HttpClient,  // injected — same shared singleton SpotifyClient uses
    // ... other injected deps unchanged
) {
    @JavascriptInterface
    fun fetchAsync(callbackId: String, url: String, method: String, headersJson: String, body: String) {
        scope.launch(Dispatchers.IO) {
            val envelope = performFetch(url, method, headersJson, body)
            withContext(Dispatchers.Main) {
                webView?.evaluateJavascript(
                    "window.__fetchCallbacks && window.__fetchCallbacks['$callbackId'] && window.__fetchCallbacks['$callbackId']('${escapeForJs(envelope)}')",
                    null,
                )
            }
        }
    }

    private suspend fun performFetch(url: String, method: String, headersJson: String, body: String): String {
        return try {
            val response = httpClient.request(url) {
                this.method = HttpMethod.parse(method.uppercase())
                parseHeaders(headersJson).forEach { (k, v) -> header(k, v) }
                if (method.uppercase() in setOf("POST", "PUT", "PATCH") || (method.uppercase() == "DELETE" && body.isNotBlank())) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            val responseBody = response.bodyAsText()
            """{"status":${response.status.value},"ok":${response.status.isSuccess()},"body":${escapeJsonString(responseBody)}}"""
        } catch (e: Throwable) {
            if (e is CancellationException) throw e  // CLAUDE.md mistake #29
            Log.e(TAG, "fetch error ($method $url): ${e.message}")
            """{"status":0,"ok":false,"body":${escapeJsonString(e.message ?: "Network error")}}"""
        }
    }
}
```

### Five invariants this preserves

1. **Envelope byte-shape unchanged.** `{"status", "ok", "body"}` JSON envelope. Plugins' `window.fetch` polyfill in `bootstrap.html` doesn't change. None of the 19 plugins need updates.
2. **Sync + async surface both preserved.** `fetch`, `fetchWithOptions`, `fetchAsync` all stay as `@JavascriptInterface` methods. `fetchAsync` is the recommended path (CLAUDE.md mistake #25); legacy sync calls keep working.
3. **No auto-injected auth.** Section 3 confirmed: shared `HttpClient` has no auth plugin installed; only per-API clients add auth. Plugin fetches inherit nothing they didn't have before.
4. **`CancellationException` rethrown explicitly.** CLAUDE.md mistake #29 is structural — `catch (e: Throwable)` would swallow cancellation. The rethrow keeps coroutine cancellation working when the bridge is torn down.
5. **Network errors mapped to `{"status":0,"ok":false}` envelope.** Same as today. Ktor's `HttpRequestTimeoutException`, DNS failures, and SSL errors all become a `0`-status envelope rather than a JS-side throw.

### What plugins gain, automatically

Without any plugin code change:

- **User-Agent.** Every plugin request gets `Parachord/0.5.0 (Android; https://parachord.app)`. MusicBrainz no longer randomly 403s a plugin call; CLAUDE.md mistake #32 closed for plugins too.
- **Coordinated timeouts.** 60s request, 15s connect, 30s socket. Eliminates inconsistent timeouts.
- **Sanitized logging.** `Authorization` headers in plugin requests get redacted in logs. Plugins making OAuth-protected calls don't leak tokens.
- **Eventual rate-limit awareness** (post-MVP). Once `RateLimitAwarePlugin` lands, plugin Spotify search and native Spotify search share back-off state.

### What plugins do NOT gain (intentional)

- **OAuth refresh.** `OAuthRefreshPlugin` only fires for *registered* refreshable hosts on 401. Plugins manage their own auth state.
- **Per-host auth credentials.** No `AuthRealm.Spotify` token gets injected. Plugins still pass their own `Authorization` headers via `headersJson`.
- **Apple Music MUT.** Plugins calling `api.music.apple.com` don't get the user's MUT auto-injected. Same security boundary as today.

### Header parsing — known footgun, scoped out

The current `parseHeaders` is a hand-rolled JSON splitter that won't handle commas inside header values, escaped quotes, or nested objects. **Not in scope for 9E.2.** Replacing it with proper JSON parsing is a quality improvement orthogonal to the transport rewire. Recommend a follow-up PR after 9E.2 is verified stable.

### iOS path

When `IosJsRuntime` (currently a stub) gets implemented in iOS playback Phase B, the iOS `NativeBridge` equivalent uses the same shared `HttpClient` — the platform engine just differs (Darwin vs OkHttp). Same JS-visible envelope.

### Verification — bake-critical

The risk in 9E.2 isn't logic complexity (the rewire is mechanical). The risk is *behavioral subtlety*: Ktor and OkHttp differ on edge cases that plugins might depend on without anyone realizing. Examples: empty body handling, redirect following depth, content-type charset negotiation, HTTP/2 multiplexing for high-concurrency plugins.

**Bake recommendation:** 24–48 hours of real plugin usage on a worktree before merging. Run search across all resolvers, kick off DJ chat with all three AI providers, browse charts (Last.fm), check concerts (Ticketmaster + SeatGeek), open an artist page (Wikipedia + Discogs metadata). Watch for any plugin reporting `{"status":0}` envelopes that previously worked.

---

## Migration order — API-by-API cutover plan

Per-API atomic cutover. Each API: create Ktor client in shared → migrate consumer call sites → migrate tests (MockWebServer → MockEngine) → delete Retrofit interface → bake. No half-migrated APIs at a phase boundary.

Order is risk-graded — simplest first, complex last, with Spotify last because it exercises `OAuthRefreshPlugin` under real load.

| # | API | Auth scheme | Why this position |
|---|---|---|---|
| 1 | **MusicBrainz** | None | Validates the entire pattern. No auth complexity. Read-only. |
| 2 | **GeoLocation** | None | Trivial follow-up — exercises "simple GET, no auth" on a different host. |
| 3 | **Ticketmaster + SeatGeek (batch)** | Query-param API key | First auth-applier — `AuthCredential.ApiKeyParam`. Both share `ConcertsRepository` consumer. |
| 4 | **ListenBrainz** | `Authorization: Token <user_token>` | First header-injected auth — `AuthCredential.TokenPrefixed`. |
| 5 | **Apple Music Catalog** | `Authorization: Bearer <dev_token>` | First Bearer — `AuthCredential.BearerToken`. Static dev token. |
| 6 | **Last.fm** | Per-request MD5 signing | Highest-complexity non-OAuth auth. Highest consumer count. Signature-correctness canary. |
| 7 | **Apple Music Library** | Bearer + MUT | **Sync extraction prereq #1.** Path-routed. Kill-switch flows for PUT/PATCH/DELETE 401. |
| 8 | **Spotify Web API** | Bearer + OAuth refresh | **Sync extraction prereq #2.** Largest consumer surface. First cutover to exercise `OAuthRefreshPlugin` under real concurrent load. |

### Per-API cutover checklist

For each API in the order above:

1. Add Ktor client in `shared/commonMain/api/`. Same name pattern as Retrofit interface (`SpotifyApi.kt` → `SpotifyClient.kt`). Inject `HttpClient` + `AuthTokenProvider`. Per-call auth applier per Section 3.
2. Verify response models are already in `shared/commonMain/api/*Models.kt`. Phase 1 moved most; spot-check + add any missing serializable data classes.
3. Migrate consumer call sites one repository at a time. After each repository, `./gradlew :app:assembleDebug` must pass.
4. Migrate tests. Existing MockWebServer-based tests become MockEngine-based.
5. Delete the Retrofit interface and its DI binding. Same commit as the last consumer cutover.
6. Smoke test on device.

### Two ordering exceptions worth flagging

- **#7 and #8 are sync-extraction prereqs.** Once both land, the sync workstream (separate brainstorm/doc) is unblocked. Don't start sync extraction until 9E.1.8 is merged + baked.
- **#6 (Last.fm) blocks more consumers than #7 + #8 combined.** If the migration pattern breaks on Last.fm's signing, it surfaces *before* Spotify. Last.fm is a deliberate "hardest-non-OAuth" canary.

---

## Phasing, sequencing, and verification gates

Three columns, because AI-assisted development compresses *active dev* dramatically while *verification and bake* stays human-paced. See `docs/plans/2026-04-25-ios-playback-design.md` for the full reasoning behind this format.

| # | Phase | What ships | Active dev | Verification / bake | Wallclock |
|---|---|---|---|---|---|
| **9E.1.0** | **Transport infrastructure** | `HttpClientFactory`, shared plugin stack, `AuthTokenProvider` + `AuthRealm` + `AuthCredential` + `OAuthTokenRefresher` interfaces, Android `AuthTokenProvider` impl, KMP-native MD5 dependency added | 0.5 day | 0.5 day (compile, smoke-test DI wiring) | 1 day |
| **9E.1.1** | **MusicBrainz** | First Ktor client. Validates pattern: location, MockEngine tests, consumer cutover, Retrofit deletion | 0.5 day | 0.5 day (search + resolver + MBID enrichment work) | 1 day |
| **9E.1.2** | **GeoLocation** | Trivial follow-up | 0.25 day | 0.25 day | 0.5 day |
| **9E.1.3** | **Ticketmaster + SeatGeek (batch)** | First auth-applier; both share `ConcertsRepository` | 0.5 day | 0.5 day (concerts screen, On Tour indicator) | 1 day |
| **9E.1.4** | **ListenBrainz** | First header-injected auth | 0.5 day | 0.5 day (history, friends, scrobble) | 1 day |
| **9E.1.5** | **Apple Music Catalog** | First Bearer | 0.5 day | 0.5 day (search, resolver) | 1 day |
| **9E.1.6** | **Last.fm** | Highest-complexity auth — signature-correctness canary | 1 day | 2 hours acute + 24h bake (real scrobble appearing on Last.fm site is the gold-standard check) | 2 days |
| **9E.1.7** | **Apple Music Library** | **Sync extraction prereq #1.** Kill-switch flows, MockWebServer→MockEngine | 1 day | 4h acute + overnight bake on real AM account | 2 days |
| **9E.1.8** | **Spotify Web API** | **Sync extraction prereq #2.** Largest consumer surface; exercises `OAuthRefreshPlugin` | 1.5 days | 8h acute + 24h bake, including forced token-refresh under N concurrent requests | 3 days |
| **9E.2** | **NativeBridge convergence** | `NativeBridge.fetch` rerouted through shared `HttpClient`. JS envelope preserved. `pluginHttpClient` deleted | 0.5–1 day | **48h plugin bake — all 19 plugins exercised across resolver search, AI chat, metadata enrichment, concerts** | 3–4 days |

**Active dev total: ~6.75–7.25 days. Wallclock total: ~16–17 days.**

The single largest wallclock contributor is the 48h plugin bake for 9E.2 — that's a calendar window, not active work.

### Three explicit verification gates

1. **End of 9E.1.1 (MusicBrainz).** First cutover proves the entire pattern. **Don't proceed to 9E.1.2 if any of:** Android `assembleDebug` fails, MusicBrainz unit tests don't run cleanly via MockEngine, search-resolver-MBID flows regress on a real device.

2. **End of 9E.1.6 (Last.fm).** Last.fm is the hardest non-OAuth canary. If MD5 signing translates incorrectly, it surfaces here before AM Library and Spotify. **Don't proceed to 9E.1.7 if any of:** scrobbles don't appear on the Last.fm site, charts load empty, metadata enrichment fails to fetch artist images.

3. **End of 9E.2.** All 19 plugins must work. **Don't merge to main if any of:** any plugin reports `{"status":0}` envelopes that previously worked, AI providers fail to respond, Bandcamp resolver returns empty results, Discogs/Wikipedia metadata stops appearing on artist pages, concert services fail. The 48h bake is for surfacing slow/intermittent failures (HTTP/2 multiplexing, charset edge cases) that don't show up in 5-minute manual tests.

### Risk callouts

**9E.1.0 — `expect/actual` Crypto for Last.fm signing.** Last.fm's `api_sig` is MD5(sorted_params + shared_secret). Kotlin/JVM has `java.security.MessageDigest`; Kotlin/Native does not. Recommend `org.kotlincrypto.hash:md5` (KMP-native MD5, audited library). Add as part of 9E.1.0 transport infrastructure so 9E.1.6 doesn't block on the dependency add.

**9E.1.7 + 9E.1.8 — sync extraction prereq sequencing.** These two unblock the sync extraction workstream. If working on sync in parallel, sync extraction must wait for *both* to land. Don't start sync extraction until 9E.1.8 is merged + baked — partially-migrated AM/Spotify clients in sync code is exactly the dual-implementation state we're avoiding.

**9E.2 — preserve plugin contract by construction, not by inspection.** The "envelope byte-shape preserved" property is a *spec*, not just a passing test. Recommend writing a contract test that captures the exact JSON envelope shape for known plugin response fields and asserts the new implementation produces an identical envelope. Test runs against MockEngine; serves as a regression dam if anyone later "improves" the envelope.

**`MockWebServer` test migration.** Recent commits `01143af` ("Add MockWebServer for Retrofit unit tests") and `8285b07` ("Collection sync: AM library MockWebServer tests") add infrastructure that 9E.1.7 must replace with `MockEngine`. Mechanical but real surface — budget time for it inside 9E.1.7's 1 day active dev.

---

## Open follow-ups

### Deliberately deferred (post-9E)

- **`RateLimitAwarePlugin`** — coordinated rate-limit state per host. Architectural seat reserved (already listed in plugin install order); not built. Useful when we observe Spotify rate-limiting in practice; not worth building speculatively.
- **Header-parsing improvement in NativeBridge** — replace hand-rolled JSON splitter with `Json.decodeFromString<Map<String,String>>`. Quality fix; orthogonal to transport rewire. Follow-up PR after 9E.2 verified stable.
- **Phase 9B (`SettingsStore` → `multiplatform-settings`)** — Android `AuthTokenProvider` impl backs onto `SecureTokenStore` + `SettingsStore` today. Phase 9B moves both shared, at which point `AuthTokenProvider` can have an iOS implementation (Keychain-backed). Not blocking 9E.1, but iOS sync needs Phase 9B before `AuthTokenProvider` is wireable on iOS.
- **Pre-emptive token refresh** — `OAuthRefreshPlugin` is purely 401-driven. If we observe latency on every token-expired request (one wasted round-trip), expiry-aware refresh can be added. Defer until measured.
- **Full Phase 8 (Coil 3)** — separately tracked. Coil 3 will use the same shared `HttpClient` automatically once it lands.

### What unblocks after this workstream

- **Sync extraction.** Once 9E.1.7 + 9E.1.8 are baked, `SpotifySyncProvider` and `AppleMusicSyncProvider` can move to `shared/commonMain/sync/`, then `SyncEngine` follows. The sync extraction brainstorm (paused at Section 1) picks up here. Separate design doc forthcoming.
- **iOS playback.** Independent of 9E in principle, but iOS playback Phase B (the iOS bootstrap that wires `:shared` into Xcode) benefits from a clean Ktor-only HTTP transport.
- **iOS sync.** Becomes a 1–2 day adapter (`IOSSyncScheduler` wrapping `BGTaskScheduler` + `BGAppRefreshTaskRequest`) once sync extraction lands.

### After 9E + 9B + sync extraction + playback orchestration extraction

The pre-iOS foundation is structurally complete. Estimated wallclock from current state:

- 9E: ~16–17 days
- 9B: ~3–4 days
- Sync extraction: ~5–7 days
- Playback orchestration extraction: ~4–5 days
- **Total pre-iOS: ~28–33 days wallclock** (~12–18 days active dev)

Then iOS playback (~16–22 days wallclock) → iOS sync (~3–5 days). End-to-end iOS MVP: ~47–60 days wallclock from clean main, of which ~20–28 are active dev.
