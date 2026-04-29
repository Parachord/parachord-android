@file:Suppress("unused")
package com.parachord.android.data.metadata

/**
 * Source-compat typealiases. The real implementation moved to
 * `com.parachord.shared.metadata.MbidEnrichmentService` so iOS can share
 * the MBID Mapper cache + enrichment logic. Disk cache I/O
 * (`<filesDir>/mbid_mapper_cache.json`) is wired in via suspend lambdas
 * in `AndroidModule`.
 */
typealias MbidEnrichmentService = com.parachord.shared.metadata.MbidEnrichmentService
typealias TrackEnrichmentRequest = com.parachord.shared.metadata.TrackEnrichmentRequest
