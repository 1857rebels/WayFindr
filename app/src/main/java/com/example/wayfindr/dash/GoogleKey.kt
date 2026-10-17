package com.example.wayfindr.dash

import com.example.wayfindr.BuildConfig

/**
 * Whether this build has a Google Maps API key (MAPS_API_KEY in secrets.properties, see
 * app/build.gradle.kts). Without one, Google maps and routing are hidden and the app uses the
 * free stack: OSM / offline maps, OSM / BRouter routing.
 */
object GoogleKey {
    /** Must match mapsKeyPlaceholder in app/build.gradle.kts. */
    const val PLACEHOLDER = "add gmaps api key here"

    fun isReal(key: String): Boolean = key.isNotBlank() && key != PLACEHOLDER

    val present: Boolean get() = isReal(BuildConfig.MAPS_API_KEY)
}
