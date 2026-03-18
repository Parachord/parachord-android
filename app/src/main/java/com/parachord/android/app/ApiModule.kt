package com.parachord.android.app

import com.parachord.android.data.api.AppleMusicApi
import com.parachord.android.data.api.LastFmApi
import com.parachord.android.data.api.MusicBrainzApi
import com.parachord.android.data.api.SeatGeekApi
import com.parachord.android.data.api.SpotifyApi
import com.parachord.android.data.api.TicketmasterApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.lang.reflect.Type
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    @Provides
    @Singleton
    @Named("musicbrainz")
    fun provideMusicBrainzRetrofit(json: Json, client: OkHttpClient): Retrofit {
        val mbClient = client.newBuilder()
            .addInterceptor(userAgentInterceptor("Parachord/0.1.0 (parachord-android)"))
            .build()

        return Retrofit.Builder()
            .baseUrl("https://musicbrainz.org/ws/2/")
            .client(mbClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideMusicBrainzApi(@Named("musicbrainz") retrofit: Retrofit): MusicBrainzApi =
        retrofit.create(MusicBrainzApi::class.java)

    @Provides
    @Singleton
    @Named("lastfm")
    fun provideLastFmRetrofit(json: Json, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://ws.audioscrobbler.com/2.0/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideLastFmApi(@Named("lastfm") retrofit: Retrofit): LastFmApi =
        retrofit.create(LastFmApi::class.java)

    @Provides
    @Singleton
    @Named("spotify")
    fun provideSpotifyRetrofit(json: Json, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.spotify.com/")
            .client(client)
            .addConverterFactory(UnitConverterFactory)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideSpotifyApi(@Named("spotify") retrofit: Retrofit): SpotifyApi =
        retrofit.create(SpotifyApi::class.java)

    @Provides
    @Singleton
    @Named("itunes")
    fun provideItunesRetrofit(json: Json, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://itunes.apple.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideAppleMusicApi(@Named("itunes") retrofit: Retrofit): AppleMusicApi =
        retrofit.create(AppleMusicApi::class.java)

    @Provides
    @Singleton
    @Named("ticketmaster")
    fun provideTicketmasterRetrofit(json: Json, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://app.ticketmaster.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideTicketmasterApi(@Named("ticketmaster") retrofit: Retrofit): TicketmasterApi =
        retrofit.create(TicketmasterApi::class.java)

    @Provides
    @Singleton
    @Named("seatgeek")
    fun provideSeatGeekRetrofit(json: Json, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.seatgeek.com/2/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideSeatGeekApi(@Named("seatgeek") retrofit: Retrofit): SeatGeekApi =
        retrofit.create(SeatGeekApi::class.java)

    private fun userAgentInterceptor(userAgent: String) = Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .build()
        )
    }

    /** Handles Response<Unit> for Spotify PUT endpoints that return 204 No Content. */
    private object UnitConverterFactory : Converter.Factory() {
        override fun responseBodyConverter(
            type: Type,
            annotations: Array<out Annotation>,
            retrofit: Retrofit,
        ): Converter<ResponseBody, *>? {
            return if (type == Unit::class.java) {
                Converter<ResponseBody, Unit> { it.close() }
            } else null
        }
    }
}
