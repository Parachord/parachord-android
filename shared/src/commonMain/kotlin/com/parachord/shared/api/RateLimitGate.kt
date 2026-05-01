package com.parachord.shared.api

import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import io.ktor.client.statement.HttpResponse
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Per-client rate-limit gate. Provides a single source of truth for
 *   1. **Cooldown** — when the upstream service has signaled a 429 (or 503
 *      for some APIs), all in-flight callers fail fast against this gate
 *      until the suggested `Retry-After` window elapses.
 *   2. **Concurrency limit** — a [Semaphore] caps simultaneous calls to a
 *      conservative number (default 2). This prevents the post-cooldown
 *      burst that would otherwise re-trip the throttle as soon as queued
 *      tasks all fire at once.
 *   3. **Inter-request delay** — a 150ms sleep before each call (after
 *      acquiring the permit). Same pacing AM uses in
 *      `AppleMusicSyncProvider.searchForTrackId` (commit `16884d1`).
 *
 * **Why this exists.** The KMP migration's Phase 9E.1.* cutovers (Apr 28,
 * 2026) swapped Retrofit/OkHttp for Ktor across SpotifyClient,
 * LastFmClient, MusicBrainzClient, and others. The Retrofit interceptor
 * chain had implicit 429/5xx retry on each. Ktor doesn't, so 429s now
 * surface as `NoTransformationFoundException` from the body parser
 * (empty 429 bodies don't deserialize into the expected typed body).
 * This gate is the typed mid-tier follow-up that
 * `SpotifySyncProvider.withRetry`'s KDoc explicitly promised after the
 * cutover. Mirrors the AM-side `ItunesRateLimitedException` pattern from
 * commit `16884d1`.
 *
 * **Design choices:**
 *  - `MAX_COOLDOWN_MS = 1 hour`: respect the upstream's `Retry-After`
 *    even when it's 30+ minutes. Capping below it is actively
 *    counterproductive — if Spotify says wait 1997s and we cap at 120s,
 *    the next call after 120s trips a fresh 429 (Spotify's window hasn't
 *    closed) and we loop indefinitely. 1 hour is a safety guardrail, not
 *    a typical case.
 *  - Single global cooldown (not per-method): rate limits are usually
 *    account/IP-wide, so one endpoint's 429 means all calls 429.
 *  - Logs first 429 of each cooldown cycle only, never per-call thereafter
 *    — a 288-track resolver fan-out would otherwise emit hundreds of
 *    identical lines.
 *
 * @param tag log tag for diagnostics (typically the client name).
 * @param maxConcurrent simultaneous in-flight permit count. Lower for
 *   stricter providers (MusicBrainz publishes 1 RPS; we use 1 + a longer
 *   delay there).
 * @param interRequestDelayMs sleep between successive calls inside the
 *   permit. Helps stagger bursts.
 * @param defaultCooldownSec used when the response has no `Retry-After`.
 * @param maxCooldownMs hard cap (defense against misbehaving servers).
 * @param loadCooldownEpochMs optional callback invoked once at construction
 *   to rehydrate the cooldown across process restarts. When the upstream
 *   service hands us a long `Retry-After` (Spotify's abuse window can be
 *   3600s), an in-memory-only gate would erase the cooldown on the next
 *   process restart and probe the server cold — Spotify often responds with
 *   a *fresh* 3600s, restarting the user's punishment clock. Persisting the
 *   epoch-ms timestamp lets the gate honor the original window across
 *   restarts and stop pestering an already-angry upstream. Pass null on
 *   tests / clients where persistence isn't needed.
 * @param saveCooldownEpochMs optional callback fired (fire-and-forget) on
 *   every cooldown write. Paired with [loadCooldownEpochMs].
 */
class RateLimitGate(
    private val tag: String,
    maxConcurrent: Int = 2,
    private val interRequestDelayMs: Long = 150L,
    private val defaultCooldownSec: Long = 30L,
    private val maxCooldownMs: Long = 60L * 60L * 1000L,
    loadCooldownEpochMs: (() -> Long)? = null,
    private val saveCooldownEpochMs: (suspend (Long) -> Unit)? = null,
) {
    private val limiter = Semaphore(maxConcurrent)

    /** Background scope for fire-and-forget cooldown persistence writes.
     *  Kept tiny — only ever does a single `setLong` per 429 cycle. */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Epoch-ms timestamp at which calls may resume. `@Volatile` so writes from
     *  one coroutine are seen on others without a memory-model surprise.
     *  Hydrated from disk via [loadCooldownEpochMs] at construction so
     *  process restarts inherit any active server-side cooldown rather than
     *  cold-probing into a fresh punishment cycle. */
    @Volatile
    private var cooldownUntilMs: Long = loadCooldownEpochMs?.invoke() ?: 0L

    /**
     * Run [block] under the gate. Throws via [exceptionFactory] without
     * making a network call if the cooldown is active. Otherwise acquires
     * a permit, sleeps the inter-request delay, runs [block], and on a
     * rate-limited response (per [isRateLimited]) sets the cooldown +
     * throws.
     *
     * @param isRateLimited matches 429 by default; pass a different
     *   predicate for APIs that signal throttling differently (e.g.
     *   MusicBrainz uses 503 + `Retry-After`).
     * @param exceptionFactory builds the typed exception to throw. Receives
     *   the `Retry-After` value in seconds (or null if absent), so callers
     *   can encode it in their own typed exceptions.
     */
    suspend fun <T> withPermit(
        isRateLimited: (HttpResponse) -> Boolean = { it.status.value == 429 },
        exceptionFactory: (retryAfterSeconds: Long?) -> Exception,
        block: suspend () -> T,
    ): T {
        checkCooldown(exceptionFactory)
        return limiter.withPermit {
            checkCooldown(exceptionFactory)
            delay(interRequestDelayMs)
            block()
        }
    }

    /**
     * Check if the most recent response indicates rate-limiting, and if
     * so set the cooldown. Call this AFTER the request returns (inside the
     * [withPermit] block), passing the response. Returns `false` if NOT
     * rate-limited (caller proceeds normally) or throws if rate-limited
     * (caller doesn't reach the `body()` decode step).
     */
    fun handleResponse(
        response: HttpResponse,
        isRateLimited: (HttpResponse) -> Boolean = { it.status.value == 429 },
        exceptionFactory: (retryAfterSeconds: Long?) -> Exception,
    ) {
        if (!isRateLimited(response)) return
        val retryAfterSec = response.headers["Retry-After"]?.toLongOrNull()
        val backoffMs = ((retryAfterSec ?: defaultCooldownSec) * 1000L).coerceAtMost(maxCooldownMs)
        val wasAlreadyLimited = currentTimeMillis() < cooldownUntilMs
        val newCooldown = currentTimeMillis() + backoffMs
        cooldownUntilMs = newCooldown
        // Persist (fire-and-forget) so the cooldown survives a process
        // restart. See class KDoc for why this matters with abuse-window
        // upstreams that hand out 3600s `Retry-After` values.
        saveCooldownEpochMs?.let { save ->
            persistScope.launch { runCatching { save(newCooldown) } }
        }
        if (!wasAlreadyLimited) {
            Log.w(tag, "$tag rate-limited (HTTP ${response.status.value}). Backing off ${backoffMs / 1000}s; subsequent calls in this window will short-circuit.")
        }
        throw exceptionFactory(retryAfterSec)
    }

    private fun checkCooldown(exceptionFactory: (retryAfterSeconds: Long?) -> Exception) {
        val now = currentTimeMillis()
        if (now < cooldownUntilMs) {
            throw exceptionFactory(((cooldownUntilMs - now + 999L) / 1000L).coerceAtLeast(1L))
        }
    }
}
