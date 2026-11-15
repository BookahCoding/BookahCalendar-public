package dev.bookah.calendar.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

@Suppress("unused")
class WeatherTest {
    private lateinit var server: MockWebServer
    private lateinit var api: WeatherApi

    @Before fun start() {
        server = MockWebServer(); server.start()
        val base = server.url("/").toString().trimEnd('/')
        api = WeatherApi(Http.newClient(), geocodingBase = base, forecastBase = base)
    }

    @After fun stop() { runCatching { server.shutdown() } }

    private val places = """{"results":[
        {"id":1,"name":"Athens","latitude":37.98376,"longitude":23.72784,"country":"Greece","admin1":"Attica","timezone":"Europe/Athens"},
        {"id":2,"name":"Athens","latitude":33.96095,"longitude":-83.37794,"country":"United States","admin1":"Georgia"},
        {"id":3,"name":"NoCoordinates"}]}"""

    private val forecast = """{"latitude":37.98,"longitude":23.73,"daily":{
        "time":["2026-11-15","2026-11-16","2026-11-17","2026-11-18"],
        "weather_code":[3,61,0,95],
        "temperature_2m_max":[17.4,15.5,19.0,null],
        "temperature_2m_min":[9.6,10.4,8.5,7.0],
        "precipitation_probability_max":[10,80,0,null]}}"""

    @Test fun placesAreParsedAndAnEntryWithoutCoordinatesIsSkipped() {
        val list = WeatherApi.parsePlaces(places)
        assertEquals(2, list.size)
        assertEquals("Athens, Attica, Greece", list[0].label)
        assertEquals(37.98376, list[0].latitude, 1e-9)
        assertEquals("Athens, Georgia, United States", list[1].label)
    }

    @Test fun noResultsMeansAnEmptyListNotAnError() = assertTrue(WeatherApi.parsePlaces("""{"generationtime_ms":0.5}""").isEmpty())

    @Test fun theForecastIsRoundedAndADayWithoutTemperaturesIsSkipped() {
        val days = WeatherApi.parseForecast(forecast)
        assertEquals(listOf("2026-11-15", "2026-11-16", "2026-11-17"), days.map { it.date })   // the 18th has no maximum
        assertEquals(17, days[0].maxC); assertEquals(10, days[0].minC); assertEquals(10, days[0].rainPercent)
        assertEquals(16, days[1].maxC)                                                          // 15.5 rounds up
        assertEquals(WeatherKind.Rain, weatherKind(days[1].code)); assertEquals(WeatherKind.Clear, weatherKind(days[2].code))
    }

    @Test fun anAnswerWithoutAForecastIsAnError() {
        try { WeatherApi.parseForecast("""{"error":true}"""); fail() } catch (e: IOException) { }
    }

    @Test fun codesMapToKinds() {
        assertEquals(WeatherKind.PartlyCloudy, weatherKind(2)); assertEquals(WeatherKind.Cloudy, weatherKind(3))
        assertEquals(WeatherKind.Fog, weatherKind(45)); assertEquals(WeatherKind.Drizzle, weatherKind(53))
        assertEquals(WeatherKind.Snow, weatherKind(73)); assertEquals(WeatherKind.Thunder, weatherKind(99))
        assertEquals(WeatherKind.Unknown, weatherKind(-1)); assertEquals(WeatherKind.Unknown, weatherKind(1234))
    }

    @Test fun thePlaceSearchSendsOnlyTheTypedNameAndLanguage() = runTest {
        server.enqueue(MockResponse().setBody(places))
        val found = api.searchPlaces("  Athens ", "el")
        assertEquals(2, found.size)
        val req = server.takeRequest()
        assertEquals("/v1/search", req.requestUrl!!.encodedPath)
        assertEquals("Athens", req.requestUrl!!.queryParameter("name")); assertEquals("el", req.requestUrl!!.queryParameter("language"))
        assertEquals(setOf("name", "count", "language", "format"), req.requestUrl!!.queryParameterNames)
        assertEquals(Http.USER_AGENT, req.getHeader("User-Agent")); assertNull(req.getHeader("Authorization"))
    }

    @Test fun theForecastRequestCarriesOnlyCoordinatesAndTheDailyFields() = runTest {
        server.enqueue(MockResponse().setBody(forecast))
        val f = api.forecast(Place("Athens", null, null, 37.98376, 23.72784), nowMs = 42L)
        assertEquals(42L, f.fetchedAtMs); assertEquals(3, f.days.size)
        val req = server.takeRequest()
        assertEquals("/v1/forecast", req.requestUrl!!.encodedPath)
        assertEquals("37.9838", req.requestUrl!!.queryParameter("latitude")); assertEquals("23.7278", req.requestUrl!!.queryParameter("longitude"))
        assertEquals(setOf("latitude", "longitude", "daily", "timezone", "forecast_days"), req.requestUrl!!.queryParameterNames)
        assertNull(req.getHeader("Authorization")); assertNull(req.getHeader("Cookie"))
    }

    @Test fun aServerErrorIsAnIoErrorSoTheOldForecastStays() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        try { api.forecast(Place("x", null, null, 1.0, 2.0), 0L); fail() } catch (e: IOException) { assertTrue("oops" !in e.message!!) }
    }
}

class ForecastFreshnessTest {
    private val today = java.time.LocalDate.of(2026, 11, 15)
    private val f = Forecast(1_000_000L, listOf(DayWeather("2026-11-15", 0, 10, 5, null)))
    private val hour = 3_600_000L

    @Test fun recentAndCoveringTodayIsFresh() = assertTrue(forecastIsFresh(f, 1_000_000L + hour, today))
    @Test fun olderThanThreeHoursIsNot() = org.junit.Assert.assertFalse(forecastIsFresh(f, 1_000_000L + 4 * hour, today))
    @Test fun aForecastThatDoesNotCoverTodayIsNot() = org.junit.Assert.assertFalse(forecastIsFresh(f, 1_000_000L + hour, today.plusDays(1)))
    @Test fun noForecastIsNot() = org.junit.Assert.assertFalse(forecastIsFresh(null, 0L, today))
    @Test fun aClockThatWentBackwardsIsNotTrusted() = org.junit.Assert.assertFalse(forecastIsFresh(f, 500_000L, today))
}
