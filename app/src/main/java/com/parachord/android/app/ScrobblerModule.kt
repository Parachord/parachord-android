package com.parachord.android.app

import com.parachord.android.playback.scrobbler.LastFmScrobbler
import com.parachord.android.playback.scrobbler.LibreFmScrobbler
import com.parachord.android.playback.scrobbler.ListenBrainzScrobbler
import com.parachord.android.playback.scrobbler.Scrobbler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Hilt module that binds all scrobbler implementations into a Set<Scrobbler>.
 *
 * Mirrors the desktop app's scrobbler-loader.js which registers all scrobbler
 * plugins. ScrobbleManager receives the full set and dispatches to each enabled one.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ScrobblerModule {

    @Binds
    @IntoSet
    abstract fun bindLastFmScrobbler(impl: LastFmScrobbler): Scrobbler

    @Binds
    @IntoSet
    abstract fun bindListenBrainzScrobbler(impl: ListenBrainzScrobbler): Scrobbler

    @Binds
    @IntoSet
    abstract fun bindLibreFmScrobbler(impl: LibreFmScrobbler): Scrobbler
}
