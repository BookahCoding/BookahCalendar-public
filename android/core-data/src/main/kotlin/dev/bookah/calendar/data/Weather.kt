package dev.bookah.calendar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate

/** A place the owner typed once in Settings (found through Open-Meteo's place search). Only its coordinates ever leave the phone. */
@kotlinx.serialization.Serializable
data class Place(val name: String, val region: String?, val country: String?, val latitude: Double, val longitude: Double) {
    /** "Athens, Attica, Greece" */
    val label: String get() = listOfNotNull(name, region, country).distinct().joinToString(", ")
}

/** One day of forecast. Temperatures in degrees Celsius. [code] is the WMO weather code as Open-Meteo gives it. */
@kotlinx.serialization.Serializable
data class DayWeather(val date: String, val code: Int, val maxC: Int, val minC: Int, val rainPercent: Int?) {
    val localDate: LocalDate get() = LocalDate.parse(date)
}

/** What kind of weather a WMO code means, coarse enough for one small icon. */
enum class WeatherKind { Clear, PartlyCloudy, Cloudy, Fog, Drizzle, Rain, Snow, Thunder, Unknown }

fun weatherKind(code: Int): WeatherKind = when (code) {
    0 -> WeatherKind.Clear
    1, 2 -> WeatherKind.PartlyCloudy
    3 -> WeatherKind.Cloudy
    45, 48 -> WeatherKind.Fog
    51, 53, 55, 56, 57 -> WeatherKind.Drizzle
    61, 63, 65, 66, 67, 80, 81, 82 -> WeatherKind.Rain
    71, 73, 75, 77, 85, 86 -> WeatherKind.Snow
    95, 96, 99 -> WeatherKind.Thunder
    else -> WeatherKind.Unknown
}

/** The forecast the phone keeps, with when it was fetched. */
@kotlinx.serialization.Serializable
data class Forecast(val fetchedAtMs: Long, val days: List<DayWeather>)

/**
 * Open-Meteo (https://open-meteo.com): free, no account, no key. The only thing sent is a place name typed by the owner (place search) or a place's
 * coordinates (forecast), plus the generic User-Agent. Nothing about the owner, his phone or his calendars. The base addresses are parameters so
 * tests can point the client at a local fake.
 */
class WeatherApi(
    private val http: OkHttpClient,
    private val geocodingBase: String = "https://geocoding-api.open-meteo.com",
    private val forecastBase: String = "https://api.open-meteo.com",
) {
    /** Up to five places matching [query]. An empty list when nothing matches. */
    suspend fun searchPlaces(query: String, language: String): List<Place> {
        val url = geocodingBase.toHttpUrl().newBuilder().addPathSegments("v1/search")
            .addQueryParameter("name", query.trim()).addQueryParameter("count", "5")
            .addQueryParameter("language", language).addQueryParameter("format", "json").build()
        return parsePlaces(get(url.toString()))
    }

    /** The daily forecast for the next 16 days at [place]; dates are the place's own local dates. */
    suspend fun forecast(place: Place, nowMs: Long): Forecast {
        val url = forecastBase.toHttpUrl().newBuilder().addPathSegments("v1/forecast")
            .addQueryParameter("latitude", "%.4f".format(java.util.Locale.ROOT, place.latitude))
            .addQueryParameter("longitude", "%.4f".format(java.util.Locale.ROOT, place.longitude))
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            .addQueryParameter("timezone", "auto").addQueryParameter("forecast_days", "16").build()
        return Forecast(nowMs, parseForecast(get(url.toString())))
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw IOException("weather service answered ${resp.code}")
            text
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parsePlaces(text: String): List<Place> {
            val results = (json.parseToJsonElement(text) as? JsonObject)?.get("results") as? JsonArray ?: return emptyList()
            return results.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val name = o["name"].str() ?: return@mapNotNull null
                val lat = (o["latitude"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                val lon = (o["longitude"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                Place(name, o["admin1"].str(), o["country"].str(), lat, lon)
            }
        }

        fun parseForecast(text: String): List<DayWeather> {
            val daily = (json.parseToJsonElement(text) as? JsonObject)?.get("daily")?.jsonObject ?: throw IOException("no forecast in the answer")
            val times = daily["time"]?.jsonArray ?: throw IOException("no days in the answer")
            val codes = daily["weather_code"]?.jsonArray
            val max = daily["temperature_2m_max"]?.jsonArray
            val min = daily["temperature_2m_min"]?.jsonArray
            val rain = daily["precipitation_probability_max"]?.jsonArray
            val out = ArrayList<DayWeather>()
            for (i in times.indices) {
                val date = times[i].str() ?: continue
                val hi = (max?.getOrNull(i) as? JsonPrimitive)?.doubleOrNull ?: continue     // a day without temperatures is skipped
                val lo = (min?.getOrNull(i) as? JsonPrimitive)?.doubleOrNull ?: continue
                val code = (codes?.getOrNull(i) as? JsonPrimitive)?.intOrNull ?: -1
                val pct = (rain?.getOrNull(i) as? JsonPrimitive)?.intOrNull
                out.add(DayWeather(date, code, Math.round(hi).toInt(), Math.round(lo).toInt(), pct))
            }
            return out
        }

        private fun kotlinx.serialization.json.JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}

/** The cached forecast can be used without asking the service again: recent enough and still covering today. */
fun forecastIsFresh(f: Forecast?, nowMs: Long, today: LocalDate, maxAgeMs: Long = 3 * 60 * 60 * 1000L): Boolean =
    f != null && nowMs - f.fetchedAtMs in 0..maxAgeMs && f.days.any { it.date == today.toString() }
