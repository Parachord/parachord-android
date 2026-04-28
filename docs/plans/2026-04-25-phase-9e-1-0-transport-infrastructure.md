# Phase 9E.1.0 — Transport Infrastructure Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build the shared HTTP transport foundation (auth interfaces, OAuth refresh plugin, User-Agent + timeout config, KMP-native MD5 for Last.fm signing) so all subsequent 9E.1.x phases can migrate their Retrofit interfaces to Ktor clients on top of a complete plugin stack.

**Architecture:** Per `docs/plans/2026-04-25-phase-9e-http-architecture-design.md`. Single shared `HttpClient` per process. Auth abstractions (`AuthTokenProvider`, `AuthRealm`, `AuthCredential`) defined in commonMain. OAuth refresh as a global Ktor plugin with single-flight semantics. No API client refactoring in this phase — that begins with 9E.1.1 (MusicBrainz). Existing Ktor clients keep their `auth: String` parameters until each is migrated to use `AuthTokenProvider`.

**Tech Stack:** Kotlin Multiplatform, Ktor 3.1.1, Koin DI, kotlincrypto-hash-md5 (new), kotlinx-coroutines (existing), Ktor MockEngine for tests.

**Worktree:** `.worktrees/9e-1-0-transport-infrastructure` on branch `feature/9e-1-0-transport-infrastructure`.

**Reference docs:**
- Design: `docs/plans/2026-04-25-phase-9e-http-architecture-design.md`
- Migration plan summary: `docs/kmp-migration-plan.md`
- Common Mistakes: `CLAUDE.md` (#29 cancellation, #32 User-Agent)

---

## Pre-flight Verification

**Step 1: Confirm worktree state**

Run: `git status && git log --oneline -3`
Expected: Clean tree on `feature/9e-1-0-transport-infrastructure`, parent commit is the gitignore-add (`eaede71`) on top of design docs.

**Step 2: Confirm baseline build passes**

Run: `./gradlew :shared:compileKotlinAndroid -q`
Expected: BUILD SUCCESSFUL within ~30s. If failure: stop and investigate before proceeding.

**Step 3: Confirm existing API client locations**

Run: `find shared/src/commonMain/kotlin/com/parachord/shared/api -name "*.kt" -type f | sort`
Expected: At least `HttpClientFactory.kt`, `SpotifyClient.kt`, `LastFmClient.kt`, `MusicBrainzClient.kt`, `AppleMusicClient.kt`, `TicketmasterClient.kt`, `SeatGeekClient.kt`, `ListenBrainzModels.kt`. If `auth/` subdirectory already exists, abort and reconcile — this plan assumes greenfield for `auth/`.

**Step 4: Confirm AppConfig exists**

Run: `find shared/src -name "AppConfig.kt" -exec head -20 {} \;`
Expected: A class with at least `version: String`, `isDebug: Boolean`. If missing `userAgent: String` field, Task 1 adds it.

---

## Task 1: Add `userAgent` field to `AppConfig` (if missing)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/config/AppConfig.kt` (path may differ — locate via Step 4)
- Modify: any actual implementations / instantiation sites (e.g., `app/.../ParachordApplication.kt`)

**Step 1: Inspect existing AppConfig**

Run: `cat $(find shared/src -name "AppConfig.kt")`
Expected: See current shape.

**Step 2: If `userAgent` already exists, skip to Task 2.**

**Step 3: If missing, write a failing test**

Create: `shared/src/commonTest/kotlin/com/parachord/shared/config/AppConfigTest.kt`

```kotlin
package com.parachord.shared.config

import kotlin.test.Test
import kotlin.test.assertEquals

class AppConfigTest {
    @Test
    fun userAgent_format_matchesParachordConvention() {
        val config = AppConfig(version = "0.5.0", isDebug = false, userAgent = "Parachord/0.5.0 (Android; https://parachord.app)")
        assertEquals("Parachord/0.5.0 (Android; https://parachord.app)", config.userAgent)
    }
}
```

**Step 4: Run test to verify it fails**

Run: `./gradlew :shared:compileTestKotlinAndroid -q`
Expected: FAIL with "no value passed for parameter 'userAgent'" (or similar — userAgent field doesn't exist yet).

**Step 5: Add `userAgent: String` field to AppConfig**

Add to AppConfig data class. Update all instantiation sites in `app/` to pass the user-agent string built from `BuildConfig.VERSION_NAME`.

**Step 6: Run test to verify pass**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.parachord.shared.config.AppConfigTest" -q`
Expected: PASS.

**Step 7: Commit**

```bash
git add shared/src app/src
git commit -m "Add userAgent field to AppConfig

Required by Phase 9E.1.0 transport infrastructure for the
DefaultRequest plugin's User-Agent header. Closes CLAUDE.md
mistake #32 (per-API OkHttpClients without User-Agent).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Add KMP-native MD5 dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `shared/build.gradle.kts`
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/api/auth/Md5SmokeTest.kt`

**Step 1: Write failing smoke test**

Create: `shared/src/commonTest/kotlin/com/parachord/shared/api/auth/Md5SmokeTest.kt`

```kotlin
package com.parachord.shared.api.auth

import org.kotlincrypto.hash.md5.MD5
import kotlin.test.Test
import kotlin.test.assertEquals

class Md5SmokeTest {
    @Test
    fun md5_emptyString_matchesKnownDigest() {
        // RFC 1321 test vector
        val digest = MD5().digest("".encodeToByteArray())
        val hex = digest.joinToString("") { byte -> ((byte.toInt() and 0xFF) or 0x100).toString(16).substring(1) }
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", hex)
    }

    @Test
    fun md5_lastFmExampleString_matchesKnownDigest() {
        // Known fixture: MD5("abc") = 900150983cd24fb0d6963f7d28e17f72
        val digest = MD5().digest("abc".encodeToByteArray())
        val hex = digest.joinToString("") { byte -> ((byte.toInt() and 0xFF) or 0x100).toString(16).substring(1) }
        assertEquals("900150983cd24fb0d6963f7d28e17f72", hex)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :shared:compileTestKotlinAndroid -q`
Expected: FAIL with "Unresolved reference: kotlincrypto" (dependency missing).

**Step 3: Add dependency to `libs.versions.toml`**

Add under `[versions]`:
```toml
kotlincrypto-hash = "0.5.6"
```

Add under `[libraries]`:
```toml
kotlincrypto-hash-md5 = { group = "org.kotlincrypto.hash", name = "md5", version.ref = "kotlincrypto-hash" }
```

**Step 4: Wire into shared module**

Modify `shared/build.gradle.kts`. Inside `commonMain.dependencies { ... }`:

```kotlin
implementation(libs.kotlincrypto.hash.md5)
```

**Step 5: Run test to verify pass**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.parachord.shared.api.auth.Md5SmokeTest" -q`
Expected: PASS (both test methods).

**Step 6: Commit**

```bash
git add gradle/libs.versions.toml shared/build.gradle.kts shared/src/commonTest
git commit -m "Add kotlincrypto-hash-md5 KMP-native MD5 for Last.fm signing

Last.fm api_sig is MD5(sorted_params + shared_secret). Kotlin/JVM
has java.security.MessageDigest; Kotlin/Native does not. The
kotlincrypto-hash-md5 library provides KMP-native MD5 with
audited implementations.

Required by Phase 9E.1.6 (Last.fm migration); landing as part
of 9E.1.0 foundation so the dependency add is decoupled from
the Last.fm cutover.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `AuthRealm` enum + `AuthCredential` sealed class

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/api/auth/AuthCredential.kt`
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/api/auth/AuthCredentialTest.kt`

**Step 1: Write failing test**

Create the test file:

```kotlin
package com.parachord.shared.api.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AuthCredentialTest {
    @Test
    fun authRealm_enumeratesAllSupportedRealms() {
        val expected = setOf(
            AuthRealm.Spotify,
            AuthRealm.AppleMusicLibrary,
            AuthRealm.ListenBrainz,
            AuthRealm.LastFm,
            AuthRealm.Ticketmaster,
            AuthRealm.SeatGeek,
            AuthRealm.Discogs,
        )
        assertEquals(expected, AuthRealm.entries.toSet())
    }

    @Test
    fun bearerToken_dataClass_equalsAndHashCode() {
        val a = AuthCredential.BearerToken("abc")
        val b = AuthCredential.BearerToken("abc")
        val c = AuthCredential.BearerToken("def")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun bearerWithMUT_carriesBothTokens() {
        val cred = AuthCredential.BearerWithMUT(devToken = "dev123", mut = "mut456")
        assertEquals("dev123", cred.devToken)
        assertEquals("mut456", cred.mut)
    }

    @Test
    fun lastFmSigned_optionalSessionKey() {
        val authPhase = AuthCredential.LastFmSigned(sharedSecret = "secret", sessionKey = null)
        val scrobblePhase = AuthCredential.LastFmSigned(sharedSecret = "secret", sessionKey = "sk")
        assertEquals(null, authPhase.sessionKey)
        assertEquals("sk", scrobblePhase.sessionKey)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :shared:compileTestKotlinAndroid -q`
Expected: FAIL with unresolved references.

**Step 3: Create `AuthCredential.kt`**

```kotlin
package com.parachord.shared.api.auth

/**
 * Logical authentication realm — used by AuthTokenProvider.tokenFor() to
 * disambiguate which credentials to return.
 *
 * Adding a new external API that requires authentication = add a new entry here.
 * Compile errors at consumer call sites surface every place that needs an
 * auth update.
 */
enum class AuthRealm {
    Spotify,
    AppleMusicLibrary,
    ListenBrainz,
    LastFm,
    Ticketmaster,
    SeatGeek,
    Discogs,
}

/**
 * Per-API credential shapes. Each Ktor client knows which subtype its realm
 * returns and casts accordingly.
 *
 * @see docs/plans/2026-04-25-phase-9e-http-architecture-design.md "Per-API auth strategy"
 */
sealed class AuthCredential {
    /** Spotify, ListenBrainz (treats Token as Bearer-ish but uses TokenPrefixed),
     *  Apple Music Catalog. */
    data class BearerToken(val accessToken: String) : AuthCredential()

    /** Apple Music Library — dev token + Music-User-Token. */
    data class BearerWithMUT(val devToken: String, val mut: String) : AuthCredential()

    /** ListenBrainz uses "Authorization: Token <key>", not "Bearer <key>". */
    data class TokenPrefixed(val prefix: String, val token: String) : AuthCredential()

    /** Ticketmaster, SeatGeek — query param key. */
    data class ApiKeyParam(val paramName: String, val value: String) : AuthCredential()

    /** Last.fm — per-request MD5 signing. sessionKey is null during auth flow,
     *  populated after token exchange. */
    data class LastFmSigned(val sharedSecret: String, val sessionKey: String?) : AuthCredential()
}
```

**Step 4: Run test to verify pass**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.parachord.shared.api.auth.AuthCredentialTest" -q`
Expected: PASS (4 tests).

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/api/auth/AuthCredential.kt shared/src/commonTest/kotlin/com/parachord/shared/api/auth/AuthCredentialTest.kt
git commit -m "Add AuthRealm enum + AuthCredential sealed class

Defines the per-API authentication shapes for the unified HTTP
transport (Phase 9E). Each Ktor client knows which AuthCredential
subtype its AuthRealm returns and applies it via small per-call
helpers (Section 3 of the design doc).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `AuthTokenProvider` + `OAuthTokenRefresher` interfaces

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/api/auth/AuthTokenProvider.kt`
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/api/auth/OAuthTokenRefresher.kt`
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/api/auth/ReauthRequiredException.kt`
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/api/auth/AuthInterfacesTest.kt`

**Step 1: Write failing test using fake implementations**

```kotlin
package com.parachord.shared.api.auth

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AuthInterfacesTest {
    @Test
    fun authTokenProvider_returnsCredentialByRealm() = runBlocking {
        val provider = FakeAuthTokenProvider(
            credentials = mapOf(
                AuthRealm.Spotify to AuthCredential.BearerToken("spotify-token"),
                AuthRealm.LastFm to AuthCredential.LastFmSigned("secret", "sk-key"),
            )
        )
        assertEquals(AuthCredential.BearerToken("spotify-token"), provider.tokenFor(AuthRealm.Spotify))
        assertEquals(AuthCredential.LastFmSigned("secret", "sk-key"), provider.tokenFor(AuthRealm.LastFm))
        assertNull(provider.tokenFor(AuthRealm.Discogs))
    }

    @Test
    fun authTokenProvider_invalidateRemovesCredential() = runBlocking {
        val provider = FakeAuthTokenProvider(
            credentials = mutableMapOf(AuthRealm.Spotify to AuthCredential.BearerToken("token"))
        )
        provider.invalidate(AuthRealm.Spotify)
        assertNull(provider.tokenFor(AuthRealm.Spotify))
    }

    @Test
    fun oauthRefresher_returnsNewTokenOnSuccess() = runBlocking {
        val refresher = FakeOAuthTokenRefresher(
            results = mapOf(AuthRealm.Spotify to AuthCredential.BearerToken("new-token"))
        )
        assertEquals(AuthCredential.BearerToken("new-token"), refresher.refresh(AuthRealm.Spotify))
    }

    @Test
    fun oauthRefresher_returnsNullOnRefreshFailure() = runBlocking {
        val refresher = FakeOAuthTokenRefresher(results = emptyMap())
        assertNull(refresher.refresh(AuthRealm.Spotify))
    }

    @Test
    fun reauthRequired_carriesRealm() {
        val ex = assertFailsWith<ReauthRequiredException> {
            throw ReauthRequiredException(AuthRealm.Spotify)
        }
        assertEquals(AuthRealm.Spotify, ex.realm)
    }
}

private class FakeAuthTokenProvider(
    private val credentials: MutableMap<AuthRealm, AuthCredential>,
) : AuthTokenProvider {
    constructor(credentials: Map<AuthRealm, AuthCredential>) : this(credentials.toMutableMap())
    override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = credentials[realm]
    override suspend fun invalidate(realm: AuthRealm) { credentials.remove(realm) }
}

private class FakeOAuthTokenRefresher(
    private val results: Map<AuthRealm, AuthCredential.BearerToken?>,
) : OAuthTokenRefresher {
    override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? = results[realm]
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :shared:compileTestKotlinAndroid -q`
Expected: FAIL with unresolved references to `AuthTokenProvider`, `OAuthTokenRefresher`, `ReauthRequiredException`.

**Step 3: Create `AuthTokenProvider.kt`**

```kotlin
package com.parachord.shared.api.auth

/**
 * Request-time authentication credential lookup. Cheap; no network.
 *
 * Implementations read from secure storage (SecureTokenStore on Android,
 * Keychain on iOS) and non-secure prefs (SettingsStore / multiplatform-settings).
 *
 * @see OAuthTokenRefresher for response-time token refresh on 401.
 */
interface AuthTokenProvider {
    /** Returns the cached credential for [realm], or null if not authenticated. */
    suspend fun tokenFor(realm: AuthRealm): AuthCredential?

    /** Marks the [realm]'s credential as invalid (e.g., after a confirmed reauth requirement). */
    suspend fun invalidate(realm: AuthRealm)
}
```

**Step 4: Create `OAuthTokenRefresher.kt`**

```kotlin
package com.parachord.shared.api.auth

/**
 * Response-time OAuth refresh. Invoked only when a 401 confirms a cached
 * token is dead. Performs a network refresh against the provider's
 * /api/token endpoint, persists the new token via SecureTokenStore, and
 * returns the new credential.
 *
 * Returns null if refresh fails (refresh token revoked / network error
 * persisting the new token / etc.) — caller should escalate to
 * [ReauthRequiredException].
 */
interface OAuthTokenRefresher {
    suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken?
}
```

**Step 5: Create `ReauthRequiredException.kt`**

```kotlin
package com.parachord.shared.api.auth

/**
 * Thrown by [OAuthRefreshPlugin] when a 401-refresh-401 sequence indicates
 * the user must re-authenticate. Call sites that care can catch and prompt
 * re-login; otherwise it propagates as a fatal error with a clear realm.
 */
class ReauthRequiredException(val realm: AuthRealm) : Exception("Re-authentication required for $realm")
```

**Step 6: Run test to verify pass**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.parachord.shared.api.auth.AuthInterfacesTest" -q`
Expected: PASS (5 tests).

**Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/api/auth shared/src/commonTest/kotlin/com/parachord/shared/api/auth/AuthInterfacesTest.kt
git commit -m "Add AuthTokenProvider, OAuthTokenRefresher, ReauthRequiredException

Defines the request-time / response-time auth split (Section 3-4 of
design doc). AuthTokenProvider is the cheap synchronous-feeling
credential lookup; OAuthTokenRefresher is the network-heavy
response-driven refresh path. Splitting the interfaces keeps the
request path fast and allows independent test mocking.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Last.fm signing helper

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/api/auth/LastFmSignature.kt`
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/api/auth/LastFmSignatureTest.kt`

**Step 1: Write failing test with known fixture**

```kotlin
package com.parachord.shared.api.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class LastFmSignatureTest {
    /**
     * Fixture from the Last.fm API authentication docs:
     * https://www.last.fm/api/desktopauth — Signing Calls
     *
     * Given params: { method=auth.gettoken, api_key=xxxxxxxxxxxxxxxx }
     * Shared secret: "tropicalsnowstorm"
     *
     * Concatenation order: alphabetically by param name, no separators.
     * Then append shared_secret. MD5 of UTF-8 bytes.
     *
     * Concat string: api_keyxxxxxxxxxxxxxxxxmethodauth.gettokentropicalsnowstorm
     * Expected MD5: 4be3b9b7a8f0e8d9... (we'll compute and pin)
     */
    @Test
    fun lastFmSignature_simpleParams_matchesKnownDigest() {
        val params = sortedMapOf(
            "method" to "auth.gettoken",
            "api_key" to "xxxxxxxxxxxxxxxx",
        )
        val sharedSecret = "tropicalsnowstorm"
        val sig = lastFmSignature(params, sharedSecret)
        // Concat: "api_keyxxxxxxxxxxxxxxxxmethodauth.gettokentropicalsnowstorm"
        // Verify with reference: echo -n "api_keyxxxxxxxxxxxxxxxxmethodauth.gettokentropicalsnowstorm" | md5sum
        // = 89f37b18ba8eaa54f7c97b66ef9b0cee (32 hex chars)
        assertEquals("89f37b18ba8eaa54f7c97b66ef9b0cee", sig)
    }

    @Test
    fun lastFmSignature_skipsApiSigParam() {
        // If api_sig is in the input map (defensive), it should NOT be included
        // in the concat — that's circular.
        val params = sortedMapOf(
            "api_sig" to "should-be-ignored",
            "api_key" to "xxxxxxxxxxxxxxxx",
            "method" to "auth.gettoken",
        )
        val sig = lastFmSignature(params, "tropicalsnowstorm")
        assertEquals("89f37b18ba8eaa54f7c97b66ef9b0cee", sig)
    }

    @Test
    fun lastFmSignature_skipsFormatParam() {
        // Per Last.fm docs, the "format" param (json/xml) is NOT signed.
        val params = sortedMapOf(
            "api_key" to "xxxxxxxxxxxxxxxx",
            "format" to "json",
            "method" to "auth.gettoken",
        )
        val sig = lastFmSignature(params, "tropicalsnowstorm")
        assertEquals("89f37b18ba8eaa54f7c97b66ef9b0cee", sig)
    }

    @Test
    fun lastFmSignature_lowercaseHex() {
        // Output must be lowercase hex; Last.fm rejects uppercase.
        val params = sortedMapOf("method" to "test")
        val sig = lastFmSignature(params, "secret")
        assertEquals(sig, sig.lowercase())
        assertEquals(32, sig.length)
    }
}
```

**Step 2: Run test — confirm fixture digest**

Before running tests, validate the expected digest from outside Kotlin:

Run: `printf '%s' "api_keyxxxxxxxxxxxxxxxxmethodauth.gettokentropicalsnowstorm" | md5sum`
Expected output: `89f37b18ba8eaa54f7c97b66ef9b0cee  -`

If the expected hex differs from `89f37b18ba8eaa54f7c97b66ef9b0cee`, update the test fixtures to match the actual command output.

**Step 3: Run failing test**

Run: `./gradlew :shared:compileTestKotlinAndroid -q`
Expected: FAIL with "Unresolved reference: lastFmSignature".

**Step 4: Implement `LastFmSignature.kt`**

```kotlin
package com.parachord.shared.api.auth

import org.kotlincrypto.hash.md5.MD5

/**
 * Last.fm API signature helper. See https://www.last.fm/api/desktopauth
 *
 * Algorithm:
 *  1. Filter out api_sig (don't sign yourself) and format (per docs, not signed).
 *  2. Sort params alphabetically by name.
 *  3. Concatenate: name1 + value1 + name2 + value2 + ... + sharedSecret.
 *  4. UTF-8 encode and MD5.
 *  5. Return lowercase hex.
 */
fun lastFmSignature(params: Map<String, String>, sharedSecret: String): String {
    val concat = buildString {
        params
            .filterKeys { it != "api_sig" && it != "format" }
            .toSortedMap()
            .forEach { (k, v) -> append(k).append(v) }
        append(sharedSecret)
    }
    val digest = MD5().digest(concat.encodeToByteArray())
    return digest.joinToString("") { byte ->
        ((byte.toInt() and 0xFF) or 0x100).toString(16).substring(1)
    }
}
```

**Step 5: Run test to verify pass**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.parachord.shared.api.auth.LastFmSignatureTest" -q`
Expected: PASS (4 tests).

**Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/api/auth/LastFmSignature.kt shared/src/commonTest/kotlin/com/parachord/shared/api/auth/LastFmSignatureTest.kt
git commit -m "Add Last.fm api_sig signing helper

Pure-function helper for the Last.fm Web Service signing protocol.
Filters api_sig (circular) and format (per docs), sorts params,
concatenates, MD5s with shared_secret, returns lowercase hex.

Lives in commonMain so the LastFmClient migration in 9E.1.6 can
sign requests cross-platform. Uses kotlincrypto-hash-md5 for
KMP-native MD5.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Expand `HttpClientFactory` signature with User-Agent + HttpTimeout

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/api/HttpClientFactory.kt`
- Modify: `shared/src/androidMain/kotlin/com/parachord/shared/api/HttpClientFactory.android.kt`
- Modify: `shared/src/iosMain/kotlin/com/parachord/shared/api/HttpClientFactory.ios.kt`
- Modify: `shared/build.gradle.kts` (add ktor-client-mock for tests if not present)
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/api/HttpClientFactoryTest.kt`

**Step 1: Add ktor-client-mock test dependency**

Add to `gradle/libs.versions.toml` under `[libraries]`:
```toml
ktor-client-mock = { group = "io.ktor", name = "ktor-client-mock", version.ref = "ktor" }
```

Add to `shared/build.gradle.kts` under `commonTest.dependencies` (create the block if absent):
```kotlin
implementation(libs.ktor.client.mock)
```

**Step 2: Write failing test**

Create `shared/src/commonTest/kotlin/com/parachord/shared/api/HttpClientFactoryTest.kt`:

```kotlin
package com.parachord.shared.api

import com.parachord.shared.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpClientFactoryTest {
    private val appConfig = AppConfig(
        version = "0.5.0-test",
        isDebug = true,
        userAgent = "Parachord/0.5.0-test (Android; https://parachord.app)",
    )

    @Test
    fun userAgent_setOnEveryRequest() = runBlocking {
        var capturedUA: String? = null
        val mock = MockEngine { request ->
            capturedUA = request.headers[HttpHeaders.UserAgent]
            respond("ok", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
        }
        val client = HttpClient(mock) {
            installSharedPlugins(Json { ignoreUnknownKeys = true }, appConfig)
        }
        client.get("https://example.com/test")
        assertEquals("Parachord/0.5.0-test (Android; https://parachord.app)", capturedUA)
    }

    @Test
    fun timeoutPluginInstalled() {
        // Smoke: construct the client with shared plugins; verify plugin is present.
        val client = HttpClient(MockEngine { respond("ok") }) {
            installSharedPlugins(Json { ignoreUnknownKeys = true }, appConfig)
        }
        // io.ktor.client.plugins.HttpTimeout is the plugin key.
        assertEquals(true, client.pluginOrNull(io.ktor.client.plugins.HttpTimeout) != null)
    }
}
```

**Step 3: Run failing test**

Run: `./gradlew :shared:compileTestKotlinAndroid -q`
Expected: FAIL with unresolved reference to `installSharedPlugins`.

**Step 4: Update `HttpClientFactory.kt` (commonMain)**

Replace contents:

```kotlin
package com.parachord.shared.api

import com.parachord.shared.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Platform-specific HTTP client factory.
 *
 * Android: uses OkHttp engine.
 * iOS: uses Darwin engine.
 *
 * Plugin install order (matters for Ktor middleware layering):
 *  1. ContentNegotiation (first — subsequent plugins read/write JSON bodies)
 *  2. Logging (early — sees behavior post-content-negotiation; sanitizes Authorization)
 *  3. DefaultRequest (User-Agent + baseline headers; before auth so it applies on retries)
 *  4. HttpTimeout (last — wraps everything in 60s/15s/30s budget)
 *
 * Subsequent phases (9E.1.0 follow-on tasks) install OAuthRefreshPlugin
 * between DefaultRequest and HttpTimeout.
 */
expect fun createHttpClient(json: Json, appConfig: AppConfig): HttpClient

internal fun HttpClientConfig<*>.installSharedPlugins(json: Json, appConfig: AppConfig) {
    install(ContentNegotiation) { json(json) }
    install(Logging) {
        level = if (appConfig.isDebug) LogLevel.HEADERS else LogLevel.INFO
        sanitizeHeader { it == HttpHeaders.Authorization }
    }
    defaultRequest {
        header(HttpHeaders.UserAgent, appConfig.userAgent)
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000     // AI endpoints take 30–60s
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
    expectSuccess = false
}
```

**Step 5: Update Android actual**

Replace `shared/src/androidMain/kotlin/com/parachord/shared/api/HttpClientFactory.android.kt`:

```kotlin
package com.parachord.shared.api

import com.parachord.shared.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

actual fun createHttpClient(json: Json, appConfig: AppConfig): HttpClient = HttpClient(OkHttp) {
    installSharedPlugins(json, appConfig)
    engine {
        config {
            connectTimeout(15, TimeUnit.SECONDS)
            readTimeout(30, TimeUnit.SECONDS)
        }
    }
}
```

**Step 6: Update iOS actual**

Replace `shared/src/iosMain/kotlin/com/parachord/shared/api/HttpClientFactory.ios.kt`:

```kotlin
package com.parachord.shared.api

import com.parachord.shared.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.serialization.json.Json

actual fun createHttpClient(json: Json, appConfig: AppConfig): HttpClient = HttpClient(Darwin) {
    installSharedPlugins(json, appConfig)
    engine {
        configureRequest {
            setAllowsCellularAccess(true)
        }
    }
}
```

**Step 7: Update existing call sites**

Run: `grep -rn "createHttpClient" --include="*.kt" .`
Expected: One or more sites in `app/.../di/AndroidModule.kt` (or similar Koin wiring). Update each call to pass `appConfig` as the second argument.

If `AppConfig` isn't currently injected at the createHttpClient call site, locate where it is created/instantiated and inject through Koin.

**Step 8: Run tests + build**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.parachord.shared.api.HttpClientFactoryTest" -q`
Expected: PASS (2 tests).

Run: `./gradlew :app:assembleDebug -q`
Expected: BUILD SUCCESSFUL.

**Step 9: Commit**

```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/api/HttpClientFactory.kt shared/src/androidMain shared/src/iosMain shared/src/commonTest/kotlin/com/parachord/shared/api/HttpClientFactoryTest.kt gradle/libs.versions.toml shared/build.gradle.kts app/src
git commit -m "Expand HttpClientFactory: User-Agent, HttpTimeout, AppConfig param

Adds DefaultRequest plugin with user-agent header (closes CLAUDE.md
mistake #32 surface for native code) and HttpTimeout (60s request,
15s connect, 30s socket — matches AI endpoint reality).

Plugin install order pinned: ContentNegotiation → Logging →
DefaultRequest → HttpTimeout. Subsequent tasks insert
OAuthRefreshPlugin between DefaultRequest and HttpTimeout.

ktor-client-mock added as a commonTest dependency for MockEngine-
based plugin testing.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: `OAuthRefreshPlugin` — basic install + 200 passthrough

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/api/transport/OAuthRefreshPlugin.kt`
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/api/transport/OAuthRefreshPluginTest.kt`

**Step 1: Write failing test — 200 passes through unchanged**

Create the test:

```kotlin
package com.parachord.shared.api.transport

import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class OAuthRefreshPluginTest {

    private val refresher = StubRefresher()
    private val provider = StubProvider()

    @Test
    fun status200_passesThroughUnchanged() = runBlocking {
        val client = HttpClient(MockEngine { respond("hello", HttpStatusCode.OK) }) {
            install(OAuthRefreshPlugin) {
                tokenProvider = provider
                tokenRefresher = refresher
                refreshableHosts = mapOf("api.spotify.com" to AuthRealm.Spotify)
            }
        }
        val response = client.get("https://api.spotify.com/v1/me")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("hello", response.bodyAsText())
        assertEquals(0, refresher.refreshCalls)
    }

    @Test
    fun status401_fromUnregisteredHost_passesThroughUnchanged() = runBlocking {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.Unauthorized) }) {
            install(OAuthRefreshPlugin) {
                tokenProvider = provider
                tokenRefresher = refresher
                refreshableHosts = mapOf("api.spotify.com" to AuthRealm.Spotify)
            }
        }
        val response = client.get("https://api.lastfm.com/foo")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0, refresher.refreshCalls)
    }
}

private class StubProvider(
    private val tokens: MutableMap<AuthRealm, AuthCredential> = mutableMapOf(),
) : AuthTokenProvider {
    override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = tokens[realm]
    override suspend fun invalidate(realm: AuthRealm) { tokens.remove(realm) }
    fun setToken(realm: AuthRealm, token: String) { tokens[realm] = AuthCredential.BearerToken(token) }
}

private class StubRefresher : OAuthTokenRefresher {
    var refreshCalls = 0
    var nextResult: AuthCredential.BearerToken? = AuthCredential.BearerToken("refreshed")
    override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? {
        refreshCalls++
        return nextResult
    }
}
```

**Step 2: Run failing test**

Run: `./gradlew :shared:compileTestKotlinAndroid -q`
Expected: FAIL — `OAuthRefreshPlugin` unresolved.

**Step 3: Implement minimal `OAuthRefreshPlugin`**

Create `shared/src/commonMain/kotlin/com/parachord/shared/api/transport/OAuthRefreshPlugin.kt`:

```kotlin
package com.parachord.shared.api.transport

import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import io.ktor.client.plugins.api.createClientPlugin

/**
 * Global Ktor plugin that intercepts 401 responses from registered OAuth realms,
 * refreshes the token (single-flight per realm), and retries the original request.
 *
 * Configured per HttpClient via:
 *   install(OAuthRefreshPlugin) {
 *     tokenProvider = provider
 *     tokenRefresher = refresher
 *     refreshableHosts = mapOf("api.spotify.com" to AuthRealm.Spotify, ...)
 *   }
 *
 * @see docs/plans/2026-04-25-phase-9e-http-architecture-design.md "OAuthRefreshPlugin"
 */
class OAuthRefreshPluginConfig {
    var tokenProvider: AuthTokenProvider? = null
    var tokenRefresher: OAuthTokenRefresher? = null
    var refreshableHosts: Map<String, AuthRealm> = emptyMap()
}

val OAuthRefreshPlugin = createClientPlugin("OAuthRefreshPlugin", ::OAuthRefreshPluginConfig) {
    val provider = pluginConfig.tokenProvider
    val refresher = pluginConfig.tokenRefresher
    val refreshableHosts = pluginConfig.refreshableHosts

    require(provider != null && refresher != null) {
        "OAuthRefreshPlugin requires tokenProvider and tokenRefresher"
    }

    // Phase 1: minimal stub. Subsequent tasks add 401-detection,
    // single-flight refresh, and retry logic.
    onResponse { /* no-op for now */ }
}
```

**Step 4: Run test to verify pass**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.parachord.shared.api.transport.OAuthRefreshPluginTest" -q`
Expected: PASS (2 tests — both validate that the plugin doesn't break passthrough behavior).

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/api/transport/OAuthRefreshPlugin.kt shared/src/commonTest/kotlin/com/parachord/shared/api/transport/OAuthRefreshPluginTest.kt
git commit -m "Add OAuthRefreshPlugin shell — passthrough behavior

Plugin installable on HttpClient. Currently a no-op for both
200 and 401 responses; subsequent tasks add 401-driven refresh,
single-flight per realm, and two-strikes ReauthRequiredException.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: `OAuthRefreshPlugin` — single 401 triggers refresh + retry

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/api/transport/OAuthRefreshPlugin.kt`
- Modify: `shared/src/commonTest/kotlin/com/parachord/shared/api/transport/OAuthRefreshPluginTest.kt`

**Step 1: Add failing test — 401 from registered host triggers refresh + retry**

Append to `OAuthRefreshPluginTest.kt`:

```kotlin
    @Test
    fun status401_fromRegisteredHost_triggersRefreshAndRetry() = runBlocking {
        var requestCount = 0
        val mock = MockEngine { request ->
            requestCount++
            when (requestCount) {
                1 -> respond("", HttpStatusCode.Unauthorized)
                2 -> {
                    // Verify retry has the new bearer
                    val authHeader = request.headers["Authorization"]
                    assertEquals("Bearer refreshed", authHeader)
                    respond("ok", HttpStatusCode.OK)
                }
                else -> error("unexpected request $requestCount")
            }
        }
        provider.setToken(AuthRealm.Spotify, "stale")
        refresher.nextResult = AuthCredential.BearerToken("refreshed")

        val client = HttpClient(mock) {
            install(OAuthRefreshPlugin) {
                tokenProvider = provider
                tokenRefresher = refresher
                refreshableHosts = mapOf("api.spotify.com" to AuthRealm.Spotify)
            }
        }
        val response = client.get("https://api.spotify.com/v1/me") {
            headers.append("Authorization", "Bearer stale")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, requestCount)
        assertEquals(1, refresher.refreshCalls)
    }
```

**Step 2: Run failing test**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.parachord.shared.api.transport.OAuthRefreshPluginTest" -q`
Expected: FAIL — single 401 currently passes through with no retry.

**Step 3: Implement 401-detection + retry**

Replace `OAuthRefreshPlugin.kt` body with the response-intercepting + retry version. The implementation needs Ktor's `HttpSend` plugin for re-execution. Look up the canonical pattern in Ktor 3.x docs (`HttpSend.intercept { request -> execute(request) }`) and implement:

```kotlin
package com.parachord.shared.api.transport

import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.shared.api.auth.ReauthRequiredException
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode

class OAuthRefreshPluginConfig {
    var tokenProvider: AuthTokenProvider? = null
    var tokenRefresher: OAuthTokenRefresher? = null
    var refreshableHosts: Map<String, AuthRealm> = emptyMap()
}

val OAuthRefreshPlugin = createClientPlugin("OAuthRefreshPlugin", ::OAuthRefreshPluginConfig) {
    val provider = requireNotNull(pluginConfig.tokenProvider) { "tokenProvider required" }
    val refresher = requireNotNull(pluginConfig.tokenRefresher) { "tokenRefresher required" }
    val refreshableHosts = pluginConfig.refreshableHosts

    client.plugin(HttpSend).intercept { request ->
        val firstAttempt = execute(request)
        if (firstAttempt.response.status != HttpStatusCode.Unauthorized) return@intercept firstAttempt

        val realm = refreshableHosts[request.url.host] ?: return@intercept firstAttempt

        val newToken = refresher.refresh(realm) ?: throw ReauthRequiredException(realm)

        // Replace Authorization header on the retry. Bearer-only swap — works for
        // Spotify and SoundCloud (the only OAuth-refreshable realms, both Bearer-based).
        request.headers.remove(HttpHeaders.Authorization)
        request.headers.append(HttpHeaders.Authorization, "Bearer ${newToken.accessToken}")

        execute(request)
    }
}
```

**Step 4: Run test to verify pass**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.parachord.shared.api.transport.OAuthRefreshPluginTest" -q`
Expected: PASS — all three tests now pass.

**Step 5: Commit**

```bash
git add shared/src/commonMain shared/src/commonTest
git commit -m "OAuthRefreshPlugin: 401 from registered host triggers refresh + retry

Uses Ktor's HttpSend interceptor to detect 401 responses,
invoke OAuthTokenRefresher, swap the Bearer token on the
original request, and re-execute. Bearer-only swap works for
Spotify and SoundCloud (the only OAuth-refreshable realms).

Single-flight semantics + thundering-herd avoidance + two-strikes
escalation come in subsequent tasks.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: `OAuthRefreshPlugin` — single-flight + thundering-herd avoidance

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/api/transport/OAuthRefreshPlugin.kt`
- Modify: `shared/src/commonTest/kotlin/com/parachord/shared/api/transport/OAuthRefreshPluginTest.kt`

**Step 1: Add failing test — N concurrent 401s trigger ONE refresh**

Append to test file:

```kotlin
    @Test
    fun concurrentRefreshes_singleFlight_oneRefreshCallForFiveConcurrentRequests() = runBlocking {
        var requestCount = 0
        val mock = MockEngine { request ->
            requestCount++
            // First request from each of the 5 concurrent calls = 401 (5 401s total)
            // Refresh happens once → all 5 retries with new token = 200
            val auth = request.headers["Authorization"] ?: ""
            if (auth.contains("stale")) respond("", HttpStatusCode.Unauthorized)
            else respond("ok", HttpStatusCode.OK)
        }
        provider.setToken(AuthRealm.Spotify, "stale")
        refresher.nextResult = AuthCredential.BearerToken("refreshed")

        val client = HttpClient(mock) {
            install(OAuthRefreshPlugin) {
                tokenProvider = provider
                tokenRefresher = refresher
                refreshableHosts = mapOf("api.spotify.com" to AuthRealm.Spotify)
            }
        }

        // Fire 5 concurrent requests — all will get 401 first then need refresh
        val deferreds = (1..5).map {
            async {
                client.get("https://api.spotify.com/v1/me/$it") {
                    headers.append("Authorization", "Bearer stale")
                }.status
            }
        }
        val results = deferreds.awaitAll()

        assertEquals(5, results.size)
        assertEquals(true, results.all { it == HttpStatusCode.OK })
        assertEquals(1, refresher.refreshCalls, "expected single-flight: only one refresh call")
    }
```

Add imports for `async`, `awaitAll`.

**Step 2: Run failing test**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.parachord.shared.api.transport.OAuthRefreshPluginTest" -q`
Expected: FAIL — `refreshCalls` will be 5, not 1.

**Step 3: Implement single-flight via per-realm Mutex with thundering-herd check**

Update plugin:

```kotlin
package com.parachord.shared.api.transport

import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.shared.api.auth.ReauthRequiredException
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OAuthRefreshPluginConfig {
    var tokenProvider: AuthTokenProvider? = null
    var tokenRefresher: OAuthTokenRefresher? = null
    var refreshableHosts: Map<String, AuthRealm> = emptyMap()
}

val OAuthRefreshPlugin = createClientPlugin("OAuthRefreshPlugin", ::OAuthRefreshPluginConfig) {
    val provider = requireNotNull(pluginConfig.tokenProvider) { "tokenProvider required" }
    val refresher = requireNotNull(pluginConfig.tokenRefresher) { "tokenRefresher required" }
    val refreshableHosts = pluginConfig.refreshableHosts

    val mutexes = mutableMapOf<AuthRealm, Mutex>()
    val mutexesGuard = Mutex()

    suspend fun mutexFor(realm: AuthRealm): Mutex = mutexesGuard.withLock {
        mutexes.getOrPut(realm) { Mutex() }
    }

    suspend fun singleFlightRefresh(realm: AuthRealm, originalToken: String?): AuthCredential.BearerToken? {
        return mutexFor(realm).withLock {
            // Thundering-herd check: did a previous holder of this mutex already refresh?
            val current = provider.tokenFor(realm) as? AuthCredential.BearerToken
            if (current != null && current.accessToken != originalToken) {
                return@withLock current
            }
            refresher.refresh(realm)
        }
    }

    client.plugin(HttpSend).intercept { request ->
        val firstAttempt = execute(request)
        if (firstAttempt.response.status != HttpStatusCode.Unauthorized) return@intercept firstAttempt

        val realm = refreshableHosts[request.url.host] ?: return@intercept firstAttempt
        val originalAuth = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")

        val newToken = singleFlightRefresh(realm, originalAuth) ?: throw ReauthRequiredException(realm)

        request.headers.remove(HttpHeaders.Authorization)
        request.headers.append(HttpHeaders.Authorization, "Bearer ${newToken.accessToken}")

        execute(request)
    }
}
```

**Step 4: Update `StubProvider` test helper to expose post-refresh token state**

Modify the stub to track refresh side-effects (the real provider would persist via SecureTokenStore). Add to the stub:

```kotlin
private class StubProvider(
    private val tokens: MutableMap<AuthRealm, AuthCredential> = mutableMapOf(),
) : AuthTokenProvider {
    override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = tokens[realm]
    override suspend fun invalidate(realm: AuthRealm) { tokens.remove(realm) }
    fun setToken(realm: AuthRealm, token: String) { tokens[realm] = AuthCredential.BearerToken(token) }
    fun setBearer(realm: AuthRealm, token: AuthCredential.BearerToken) { tokens[realm] = token }
}
```

And update `StubRefresher` to write through:

```kotlin
private class StubRefresher(private val provider: StubProvider) : OAuthTokenRefresher {
    var refreshCalls = 0
    var nextResult: AuthCredential.BearerToken? = AuthCredential.BearerToken("refreshed")
    override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? {
        refreshCalls++
        nextResult?.let { provider.setBearer(realm, it) }
        return nextResult
    }
}
```

Update test class to wire `StubRefresher(provider)`.

**Step 5: Run test to verify pass**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.parachord.shared.api.transport.OAuthRefreshPluginTest" -q`
Expected: PASS — `refreshCalls == 1` for 5 concurrent 401s.

**Step 6: Commit**

```bash
git add shared/src/commonMain shared/src/commonTest
git commit -m "OAuthRefreshPlugin: single-flight refresh + thundering-herd avoidance

Per-realm Mutex prevents N concurrent 401s from triggering N refresh
requests. Inside the lock, before refreshing, the plugin checks
whether a prior refresh already updated the cached token — if so,
skip our refresh and reuse theirs.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: `OAuthRefreshPlugin` — two-strikes throws `ReauthRequiredException`

**Files:**
- Modify: `shared/src/commonTest/kotlin/com/parachord/shared/api/transport/OAuthRefreshPluginTest.kt`

**Step 1: Add failing test — 401 → refresh → 401 throws `ReauthRequiredException`**

Append:

```kotlin
    @Test
    fun two401InARow_throwsReauthRequired() = runBlocking {
        val mock = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        provider.setToken(AuthRealm.Spotify, "stale")
        refresher.nextResult = AuthCredential.BearerToken("also-bad")

        val client = HttpClient(mock) {
            install(OAuthRefreshPlugin) {
                tokenProvider = provider
                tokenRefresher = refresher
                refreshableHosts = mapOf("api.spotify.com" to AuthRealm.Spotify)
            }
        }

        val ex = assertFailsWith<ReauthRequiredException> {
            client.get("https://api.spotify.com/v1/me") {
                headers.append("Authorization", "Bearer stale")
            }
        }
        assertEquals(AuthRealm.Spotify, ex.realm)
    }

    @Test
    fun refreshReturnsNull_throwsReauthRequired() = runBlocking {
        val mock = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        provider.setToken(AuthRealm.Spotify, "stale")
        refresher.nextResult = null  // refresh failed

        val client = HttpClient(mock) {
            install(OAuthRefreshPlugin) {
                tokenProvider = provider
                tokenRefresher = refresher
                refreshableHosts = mapOf("api.spotify.com" to AuthRealm.Spotify)
            }
        }

        assertFailsWith<ReauthRequiredException> {
            client.get("https://api.spotify.com/v1/me") {
                headers.append("Authorization", "Bearer stale")
            }
        }
    }
```

Add imports for `assertFailsWith`.

**Step 2: Run failing test**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.parachord.shared.api.transport.OAuthRefreshPluginTest" -q`
Expected: The first new test FAILS — currently the plugin happily retries with the new (still-bad) token and returns 401, not throwing.

**Step 3: Add two-strikes check to plugin**

Update the intercept block:

```kotlin
    client.plugin(HttpSend).intercept { request ->
        val firstAttempt = execute(request)
        if (firstAttempt.response.status != HttpStatusCode.Unauthorized) return@intercept firstAttempt

        val realm = refreshableHosts[request.url.host] ?: return@intercept firstAttempt
        val originalAuth = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")

        val newToken = singleFlightRefresh(realm, originalAuth) ?: throw ReauthRequiredException(realm)

        request.headers.remove(HttpHeaders.Authorization)
        request.headers.append(HttpHeaders.Authorization, "Bearer ${newToken.accessToken}")

        val secondAttempt = execute(request)
        if (secondAttempt.response.status == HttpStatusCode.Unauthorized) {
            throw ReauthRequiredException(realm)
        }
        secondAttempt
    }
```

**Step 4: Run test to verify pass**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.parachord.shared.api.transport.OAuthRefreshPluginTest" -q`
Expected: PASS (all OAuthRefreshPlugin tests, including the new two-strikes ones).

**Step 5: Commit**

```bash
git add shared/src/commonMain shared/src/commonTest
git commit -m "OAuthRefreshPlugin: two-strikes throws ReauthRequiredException

If the retry after a successful refresh ALSO returns 401, the refresh
produced a still-bad token (clock skew, revoked grant, malformed
refresh response). Throw ReauthRequiredException(realm) so call
sites that care can prompt re-login.

Also throws when the refresher returns null (refresh itself failed
— refresh token revoked / network error / etc.).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: Wire `OAuthRefreshPlugin` into `HttpClientFactory.installSharedPlugins`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/api/HttpClientFactory.kt`
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/api/HttpClientFactoryTest.kt`

**Step 1: Update `createHttpClient` signature to take `AuthTokenProvider` + `OAuthTokenRefresher`**

The expect signature must accept the new dependencies. Update:

```kotlin
expect fun createHttpClient(
    json: Json,
    appConfig: AppConfig,
    authProvider: AuthTokenProvider,
    tokenRefresher: OAuthTokenRefresher,
): HttpClient

internal fun HttpClientConfig<*>.installSharedPlugins(
    json: Json,
    appConfig: AppConfig,
    authProvider: AuthTokenProvider,
    tokenRefresher: OAuthTokenRefresher,
) {
    install(ContentNegotiation) { json(json) }
    install(Logging) {
        level = if (appConfig.isDebug) LogLevel.HEADERS else LogLevel.INFO
        sanitizeHeader { it == HttpHeaders.Authorization }
    }
    defaultRequest {
        header(HttpHeaders.UserAgent, appConfig.userAgent)
    }
    install(OAuthRefreshPlugin) {
        this.tokenProvider = authProvider
        this.tokenRefresher = tokenRefresher
        this.refreshableHosts = mapOf(
            "api.spotify.com" to AuthRealm.Spotify,
            // SoundCloud added when 9E.x migrates SoundCloud — see design doc.
        )
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
    expectSuccess = false
}
```

**Step 2: Update both platform actuals to pass through the new params**

Android:

```kotlin
actual fun createHttpClient(
    json: Json,
    appConfig: AppConfig,
    authProvider: AuthTokenProvider,
    tokenRefresher: OAuthTokenRefresher,
): HttpClient = HttpClient(OkHttp) {
    installSharedPlugins(json, appConfig, authProvider, tokenRefresher)
    engine {
        config {
            connectTimeout(15, TimeUnit.SECONDS)
            readTimeout(30, TimeUnit.SECONDS)
        }
    }
}
```

iOS analogous.

**Step 3: Update existing tests to pass stub provider/refresher**

Update `HttpClientFactoryTest.kt` to inject stubs (move them from `OAuthRefreshPluginTest.kt` to a shared test helper if desired):

```kotlin
private val stubProvider = object : AuthTokenProvider {
    override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = null
    override suspend fun invalidate(realm: AuthRealm) {}
}
private val stubRefresher = object : OAuthTokenRefresher {
    override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? = null
}
```

**Step 4: Update Koin wiring in `app/.../di/AndroidModule.kt`**

Find the `single { createHttpClient(get()) }` binding (or equivalent) and update to pass the new dependencies. The `AuthTokenProvider` impl comes from Task 12 — for now, write a temporary stub binding that throws if invoked:

```kotlin
single<AuthTokenProvider> {
    object : AuthTokenProvider {
        override suspend fun tokenFor(realm: AuthRealm) =
            error("AuthTokenProvider impl not yet wired — see Task 12")
        override suspend fun invalidate(realm: AuthRealm) {}
    }
}
single<OAuthTokenRefresher> {
    object : OAuthTokenRefresher {
        override suspend fun refresh(realm: AuthRealm) =
            error("OAuthTokenRefresher impl not yet wired — see Task 12")
    }
}
single { createHttpClient(get(), get(), get(), get()) }
```

The stub bindings get replaced by real implementations in Task 12.

**Step 5: Run tests + build**

Run: `./gradlew :shared:testDebugUnitTest -q`
Expected: PASS — all tests still green.

Run: `./gradlew :app:assembleDebug -q`
Expected: BUILD SUCCESSFUL.

**Step 6: Commit**

```bash
git add shared/src app/src
git commit -m "Wire OAuthRefreshPlugin into HttpClientFactory.installSharedPlugins

createHttpClient signature now requires AuthTokenProvider + OAuthTokenRefresher.
Plugin install order is final: ContentNegotiation → Logging →
DefaultRequest → OAuthRefreshPlugin → HttpTimeout.

Spotify is the only refreshable realm registered for now (SoundCloud
will be added when its native client migrates from raw OkHttp calls
in a later 9E phase).

Temporary stub bindings in Koin throw if invoked; real Android impl
lands in Task 12.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: Android `AuthTokenProvider` + `OAuthTokenRefresher` implementations

**Files:**
- Create: `app/src/main/java/com/parachord/android/data/auth/AndroidAuthTokenProvider.kt`
- Create: `app/src/main/java/com/parachord/android/data/auth/AndroidOAuthTokenRefresher.kt`
- Test: `app/src/test/java/com/parachord/android/data/auth/AndroidAuthTokenProviderTest.kt`
- Modify: `app/src/main/java/com/parachord/android/di/AndroidModule.kt` (Koin wiring)

**Step 1: Inspect existing `SecureTokenStore` + `SettingsStore` API**

Run: `grep -n "fun " app/src/main/java/com/parachord/android/data/store/SecureTokenStore.kt | head -20`
Run: `grep -n "fun " app/src/main/java/com/parachord/android/data/store/SettingsStore.kt | head -30`
Note the available token getters/setters (Spotify access/refresh, AM dev/MUT, Last.fm session, etc.).

**Step 2: Write failing test**

Create `app/src/test/java/com/parachord/android/data/auth/AndroidAuthTokenProviderTest.kt`:

```kotlin
package com.parachord.android.data.auth

import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidAuthTokenProviderTest {
    @Test
    fun spotifyRealm_returnsBearerTokenFromSecureStore() = runBlocking {
        val secureStore = mockk<com.parachord.android.data.store.SecureTokenStore>()
        coEvery { secureStore.getSpotifyAccessToken() } returns "spotify-token"
        val provider = AndroidAuthTokenProvider(secureStore, mockk())

        val cred = provider.tokenFor(AuthRealm.Spotify)
        assertEquals(AuthCredential.BearerToken("spotify-token"), cred)
    }

    @Test
    fun spotifyRealm_returnsNullWhenNotAuthed() = runBlocking {
        val secureStore = mockk<com.parachord.android.data.store.SecureTokenStore>()
        coEvery { secureStore.getSpotifyAccessToken() } returns null
        val provider = AndroidAuthTokenProvider(secureStore, mockk())

        assertNull(provider.tokenFor(AuthRealm.Spotify))
    }

    @Test
    fun appleMusicLibrary_returnsBearerWithMUT() = runBlocking {
        val secureStore = mockk<com.parachord.android.data.store.SecureTokenStore>()
        coEvery { secureStore.getAppleMusicDeveloperToken() } returns "dev-token"
        coEvery { secureStore.getAppleMusicUserToken() } returns "mut-token"
        val provider = AndroidAuthTokenProvider(secureStore, mockk())

        val cred = provider.tokenFor(AuthRealm.AppleMusicLibrary)
        assertEquals(AuthCredential.BearerWithMUT("dev-token", "mut-token"), cred)
    }

    @Test
    fun appleMusicLibrary_returnsNullWhenMutMissing() = runBlocking {
        val secureStore = mockk<com.parachord.android.data.store.SecureTokenStore>()
        coEvery { secureStore.getAppleMusicDeveloperToken() } returns "dev-token"
        coEvery { secureStore.getAppleMusicUserToken() } returns null
        val provider = AndroidAuthTokenProvider(secureStore, mockk())

        assertNull(provider.tokenFor(AuthRealm.AppleMusicLibrary))
    }

    @Test
    fun lastFm_returnsLastFmSignedWithSecretAndSession() = runBlocking {
        val secureStore = mockk<com.parachord.android.data.store.SecureTokenStore>()
        val settings = mockk<com.parachord.android.data.store.SettingsStore>()
        coEvery { secureStore.getLastFmSessionKey() } returns "sk-key"
        // Shared secret comes from BuildConfig — validate it's read via AppConfig path
        // (precise wiring depends on existing SettingsStore API; adjust per inspection)
        val provider = AndroidAuthTokenProvider(secureStore, settings)

        val cred = provider.tokenFor(AuthRealm.LastFm) as? AuthCredential.LastFmSigned
        assertEquals("sk-key", cred?.sessionKey)
    }
}
```

**Step 3: Run failing test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.parachord.android.data.auth.AndroidAuthTokenProviderTest" -q`
Expected: FAIL — `AndroidAuthTokenProvider` doesn't exist.

**Step 4: Implement `AndroidAuthTokenProvider`**

The exact realm-to-method mapping depends on the existing `SecureTokenStore` and `SettingsStore` API. Pseudocode template:

```kotlin
package com.parachord.android.data.auth

import com.parachord.android.data.store.SecureTokenStore
import com.parachord.android.data.store.SettingsStore
import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider

/**
 * Android implementation of AuthTokenProvider.
 *
 * Reads from SecureTokenStore (EncryptedSharedPreferences) for OAuth tokens
 * and BYO API keys. Reads non-secure config from SettingsStore (DataStore).
 *
 * After Phase 9B (SettingsStore → multiplatform-settings), this implementation
 * moves to commonMain with platform-specific Keychain/SharedPreferences actuals.
 */
class AndroidAuthTokenProvider(
    private val secureStore: SecureTokenStore,
    private val settings: SettingsStore,
) : AuthTokenProvider {

    override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = when (realm) {
        AuthRealm.Spotify -> secureStore.getSpotifyAccessToken()?.let(AuthCredential::BearerToken)

        AuthRealm.AppleMusicLibrary -> {
            val dev = secureStore.getAppleMusicDeveloperToken()
            val mut = secureStore.getAppleMusicUserToken()
            if (dev != null && mut != null) AuthCredential.BearerWithMUT(dev, mut) else null
        }

        AuthRealm.ListenBrainz -> secureStore.getListenBrainzUserToken()?.let {
            AuthCredential.TokenPrefixed(prefix = "Token", token = it)
        }

        AuthRealm.LastFm -> {
            val sessionKey = secureStore.getLastFmSessionKey()
            // Shared secret from BuildConfig via AppConfig — adjust per existing wiring
            AuthCredential.LastFmSigned(sharedSecret = lastFmSharedSecret(), sessionKey = sessionKey)
        }

        AuthRealm.Ticketmaster -> secureStore.getTicketmasterApiKey()?.let {
            AuthCredential.ApiKeyParam(paramName = "apikey", value = it)
        }

        AuthRealm.SeatGeek -> secureStore.getSeatGeekClientId()?.let {
            AuthCredential.ApiKeyParam(paramName = "client_id", value = it)
        }

        AuthRealm.Discogs -> secureStore.getDiscogsToken()?.let {
            AuthCredential.TokenPrefixed(prefix = "Discogs token=", token = it)
        }
    }

    override suspend fun invalidate(realm: AuthRealm) {
        when (realm) {
            AuthRealm.Spotify -> secureStore.clearSpotifyTokens()
            AuthRealm.AppleMusicLibrary -> secureStore.clearAppleMusicTokens()
            AuthRealm.ListenBrainz -> secureStore.clearListenBrainzToken()
            AuthRealm.LastFm -> secureStore.clearLastFmSessionKey()
            AuthRealm.Ticketmaster -> secureStore.clearTicketmasterApiKey()
            AuthRealm.SeatGeek -> secureStore.clearSeatGeekClientId()
            AuthRealm.Discogs -> secureStore.clearDiscogsToken()
        }
    }

    private fun lastFmSharedSecret(): String =
        com.parachord.android.BuildConfig.LASTFM_SHARED_SECRET
}
```

> ⚠️ The exact `secureStore.*` method names depend on `SecureTokenStore`'s current API. If a method doesn't exist (e.g., `getDiscogsToken`), either add it to `SecureTokenStore` or stub the realm to return null until that backing storage exists. Discogs in particular may be plugin-only today.

**Step 5: Implement `AndroidOAuthTokenRefresher`**

Similarly create `AndroidOAuthTokenRefresher.kt` that wraps the existing `OAuthManager.refreshIfNeeded(...)` (or its equivalent). Returns `AuthCredential.BearerToken` on success, `null` on refresh failure.

```kotlin
package com.parachord.android.data.auth

import com.parachord.android.auth.OAuthManager
import com.parachord.android.data.store.SecureTokenStore
import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.OAuthTokenRefresher

class AndroidOAuthTokenRefresher(
    private val oauthManager: OAuthManager,
    private val secureStore: SecureTokenStore,
) : OAuthTokenRefresher {
    override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? = when (realm) {
        AuthRealm.Spotify -> {
            try {
                val newToken = oauthManager.refreshSpotifyToken() ?: return null
                AuthCredential.BearerToken(newToken)
            } catch (_: Exception) {
                null
            }
        }
        else -> null  // Other realms not OAuth-refreshable
    }
}
```

**Step 6: Wire into Koin (replace stub bindings from Task 11)**

In `AndroidModule.kt`:

```kotlin
single<AuthTokenProvider> { AndroidAuthTokenProvider(get(), get()) }
single<OAuthTokenRefresher> { AndroidOAuthTokenRefresher(get(), get()) }
single { createHttpClient(get(), get(), get(), get()) }
```

**Step 7: Run tests + build**

Run: `./gradlew :app:testDebugUnitTest --tests "com.parachord.android.data.auth.*" -q`
Expected: PASS.

Run: `./gradlew :app:assembleDebug -q`
Expected: BUILD SUCCESSFUL.

**Step 8: Commit**

```bash
git add app/src
git commit -m "Add Android AuthTokenProvider + OAuthTokenRefresher implementations

AndroidAuthTokenProvider reads OAuth tokens from SecureTokenStore
(EncryptedSharedPreferences) and config from SettingsStore (DataStore).
Maps each AuthRealm to its corresponding AuthCredential subtype.

AndroidOAuthTokenRefresher delegates to the existing OAuthManager
for Spotify token refresh; other realms aren't OAuth-refreshable.

After Phase 9B, both implementations move to commonMain with
expect/actual platform storage adapters. Today they live in :app.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: Final integration verification

**Files:** none new — verification only.

**Step 1: Run all unit tests**

Run: `./gradlew :shared:testDebugUnitTest :app:testDebugUnitTest -q`
Expected: All tests PASS — green run from main + new auth tests.

**Step 2: Run lint and assemble**

Run: `./gradlew :app:assembleDebug -q`
Expected: BUILD SUCCESSFUL.

**Step 3: Smoke-test on a device or emulator**

Run: `./gradlew :app:installDebug && adb shell am start -n com.parachord.android.debug/com.parachord.android.MainActivity`

Manual checks:
- App launches without DI / startup crash.
- Library loads (verifies Koin can resolve `HttpClient` and consumers).
- Search a track (exercises Spotify / MusicBrainz; existing Retrofit clients still work — they don't use the new HttpClient yet).

**Step 4: Verify auth tokens still flow**

Manual: check Settings → connected services. Spotify and Apple Music tokens should still be present (we didn't touch SecureTokenStore — just added a new reader).

**Step 5: Smoke test Logcat for OAuthRefreshPlugin / Auth issues**

Run: `adb logcat -s KtorHttp Parachord:I -d | tail -50`
Expected: No exceptions related to `OAuthRefreshPlugin`, `AuthTokenProvider`, `Koin` resolution.

**Step 6: Document next phase**

Note in commit message that 9E.1.0 is complete and the next phase is 9E.1.1 (MusicBrainz cutover) — first migration to use the new auth+plugin foundation.

**Step 7: Final verification commit**

```bash
git commit --allow-empty -m "Phase 9E.1.0 complete — transport infrastructure ready

- KMP-native MD5 dependency added (kotlincrypto-hash-md5)
- AuthRealm + AuthCredential + AuthTokenProvider + OAuthTokenRefresher
  + ReauthRequiredException defined in shared/commonMain
- Last.fm signing helper (lastFmSignature) implemented and tested
  against known fixtures
- HttpClientFactory expanded: User-Agent (closes #32 surface for
  native code), HttpTimeout (60s/15s/30s), expectSuccess=false
- OAuthRefreshPlugin: response-driven, single-flight per realm,
  thundering-herd avoidance, two-strikes throws ReauthRequiredException
- Plugin install order pinned: ContentNegotiation → Logging →
  DefaultRequest → OAuthRefreshPlugin → HttpTimeout
- Android AuthTokenProvider + OAuthTokenRefresher implementations
  wrapping SecureTokenStore + OAuthManager
- Koin wired; app builds and launches

Next: 9E.1.1 (MusicBrainz client cutover — first migration that
consumes the new infrastructure).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Verification Gate (per design doc)

Before merging this branch to main:

1. **All shared module unit tests pass** (`./gradlew :shared:testDebugUnitTest`).
2. **App builds and assembles** (`./gradlew :app:assembleDebug`).
3. **App launches without DI failure** on a real device.
4. **Existing Retrofit-based flows still work** — search, library, sync, scrobble, AI chat, concerts. The new `HttpClient` is wired but no consumer uses it yet (that starts in 9E.1.1).
5. **No regressions in `Logcat`** for `Koin`, `Ktor`, `OAuth`, or any startup component.

If any of the above fail, **don't merge**. Investigate first; the design doc's risk callouts apply.

After 9E.1.0 merges, spin up a fresh worktree (`feature/9e-1-1-musicbrainz-client`) for 9E.1.1, which is the first cutover that actually exercises the new plugin stack on a real consumer.
