package com.parachord.android.deeplink

import com.parachord.android.playback.PlaybackController
import com.parachord.shared.deeplink.DeepLinkAction
import com.parachord.shared.deeplink.ProtocolPlayInput
import com.parachord.shared.deeplink.ProtocolPlayTeardown
import com.parachord.shared.deeplink.RadioMode
import java.net.URLEncoder

/** One-shot result for the toast / log surface. Mirrors [ProtocolPlayResult]. */
sealed class PlayRadioResult {
    /** Mode B title-bearing successfully kicked off (Last.fm fetch starts in background). */
    data class StartedModeB(val displayName: String) : PlayRadioResult()
    /** Mode C / Mode B artist-only successfully kicked off — N tracks in pool. */
    data class StartedModeC(val displayName: String, val trackCount: Int) : PlayRadioResult()
    data class Failed(val reason: String) : PlayRadioResult()
}

/**
 * Orchestrator for `parachord://play/radio` (Phase 3, issue #121).
 *
 * Three real branches once you split Mode B by whether a track hint is
 * present:
 *
 *  - **Mode B title-bearing** — `?artist=<name>&title=<hint>`. Per-track
 *    spinoff via Last.fm `track.getsimilar` — same backend as the in-app
 *    right-click → Spinoff. Tears down prior playback then dispatches
 *    into [PlaybackController.startSpinoffWithSeed].
 *  - **Mode B artist-only** — `?artist=<name>` (no title). Synthesizes a
 *    ListenBrainz `lb-radio` URL and routes through the SAME pool path
 *    as Mode C. The synthesized URL is reused as the refill URL so the
 *    radio keeps generating fresh tracks across pool drains. The LB
 *    token auto-attach plugin (Task 2) supplies the user's token for
 *    free since the host is `api.listenbrainz.org`.
 *  - **Mode C (pool-based)** — `?url=`/`?tracks=`/`?refill=`. Delegates
 *    to [ProtocolPlayHandler] for resolve + teardown + entity build,
 *    then [PlaybackController.startPoolBasedSpinoff] for the pool
 *    kick-off. The acknowledgment toast ("Building radio…") is the VM's
 *    responsibility — emitted before [dispatch] runs since URL fetch
 *    can take seconds and the user needs feedback.
 *
 * **Teardown semantics**: every branch clears the queue + exits any
 * active spinoff + stops listen-along, matching the Phase 2
 * album/playlist behavior. The in-app right-click → Spinoff path does
 * NOT call teardown (it preserves the queue and returns to it on exit),
 * but the deeplink path is "start fresh radio" semantics, so teardown
 * is correct here.
 */
class PlayRadioDispatcher(
    private val playbackController: PlaybackController,
    private val teardown: ProtocolPlayTeardown,
    private val protocolPlayHandler: ProtocolPlayHandler,
) {
    suspend fun dispatch(action: DeepLinkAction.PlayRadio): PlayRadioResult {
        return when (val mode = action.mode) {
            is RadioMode.ArtistSeed -> {
                if (mode.title != null) {
                    // Title-bearing seed: per-track spinoff via Last.fm
                    // track.getsimilar (same backend as in-app spinoff).
                    teardown.prepareForNewPlayback()
                    val displayName = action.name
                        ?: "Radio: ${mode.artist} – ${mode.title}"
                    playbackController.startSpinoffWithSeed(
                        seedArtist = mode.artist,
                        seedTitle = mode.title,
                        displayName = displayName,
                        // Mode B is "start fresh radio now" semantics — kick
                        // the first pool track immediately rather than
                        // waiting for an existing song to finish (the
                        // teardown above clears the queue but doesn't stop
                        // whatever's currently playing).
                        kickStartFirstTrack = true,
                    )
                    PlayRadioResult.StartedModeB(displayName)
                } else {
                    // Artist-only seed: route through ListenBrainz LB
                    // Radio via the Mode C pool path. The synthesized
                    // URL is also the refill URL — same endpoint keeps
                    // generating fresh tracks across drains. LB token
                    // auto-attach (Task 2) applies the user's token
                    // since the host is api.listenbrainz.org.
                    val lbRadioUrl = buildLbRadioUrl(artist = mode.artist)
                    val displayName = action.name ?: "Radio: ${mode.artist}"
                    val syntheticAction = DeepLinkAction.PlayRadio(
                        mode = RadioMode.PoolBased,
                        input = ProtocolPlayInput(url = lbRadioUrl),
                        refillUrl = lbRadioUrl,
                        name = displayName,
                    )
                    dispatchPoolBased(syntheticAction)
                }
            }
            is RadioMode.PoolBased -> dispatchPoolBased(action)
        }
    }

    /**
     * Shared Mode C pool path — used by both real Mode C deeplinks and
     * the synthesized LB Radio action for Mode B artist-only.
     *
     * Wires the refill fetcher BEFORE the handler runs so
     * `startPoolBasedSpinoff()` — invoked synchronously inside `handle()` —
     * sees a configured fetcher when it sets the refillUrl. The fetcher
     * is a closure over the handler's `resolveTrackList(url)`, so refills
     * go through the same SSRF guard / JSPF parser / LB-token auto-attach
     * as the initial pool fetch.
     *
     * Shows the mini-player spinner during the URL fetch + parse + resolve
     * cascade. try/finally guarantees the flag clears on failure paths.
     */
    private suspend fun dispatchPoolBased(action: DeepLinkAction.PlayRadio): PlayRadioResult {
        playbackController.setPoolFetcher { url ->
            protocolPlayHandler.resolveTrackList(url)
        }
        playbackController.setPlaybarLoading(true)
        return try {
            when (val r = protocolPlayHandler.handle(action)) {
                is ProtocolPlayResult.Started ->
                    PlayRadioResult.StartedModeC(r.displayName, r.trackCount)
                is ProtocolPlayResult.Failed ->
                    PlayRadioResult.Failed(r.reason)
            }
        } finally {
            playbackController.setPlaybarLoading(false)
        }
    }

    /**
     * Synthesize a ListenBrainz LB Radio URL for an artist-only seed.
     *
     * Prompt syntax follows troi.readthedocs.io/en/latest/lb_radio.html:
     * `artist:(<name>)` → seed by artist similarity. Mode `easy` is the
     * troi default — most-relevant data, fewest deep cuts.
     *
     * The colon and parentheses in the prompt are reserved characters
     * in a query string. URLEncoder encodes them to `%3A`, `%28`, `%29`
     * — what the LB endpoint expects.
     */
    private fun buildLbRadioUrl(artist: String): String {
        val prompt = "artist:($artist)"
        val encoded = URLEncoder.encode(prompt, "UTF-8")
        return "https://api.listenbrainz.org/1/explore/lb-radio?prompt=$encoded&mode=easy"
    }
}
