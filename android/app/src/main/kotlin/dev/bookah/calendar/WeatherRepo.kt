package dev.bookah.calendar

import dev.bookah.calendar.data.Forecast
import dev.bookah.calendar.data.KeystoreVault
import dev.bookah.calendar.data.Place
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What the phone keeps about the weather: the place the owner typed (encrypted, like the other private settings, never in a file or log)
 * and the last forecast (so the Schedule still shows weather when the phone is offline). Nothing here is sent anywhere by itself.
 */
class WeatherRepo(private val vault: KeystoreVault, private val cache: File) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun place(): Place? =
        vault.get(KEY)?.let { runCatching { json.decodeFromString(Place.serializer(), it) }.getOrNull() }

    suspend fun savePlace(p: Place) = vault.put(KEY, json.encodeToString(Place.serializer(), p))

    /** Removing the place also removes the cached forecast for it. */
    suspend fun clear() { vault.remove(KEY); runCatching { cache.delete() } }

    fun cached(): Forecast? = runCatching { json.decodeFromString(Forecast.serializer(), cache.readText()) }.getOrNull()

    fun saveCache(f: Forecast) { runCatching { cache.writeText(json.encodeToString(Forecast.serializer(), f)) } }

    private companion object { const val KEY = "weather_place" }
}
