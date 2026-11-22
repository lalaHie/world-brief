package tw.worldbrief.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class WeatherStoreTest {
    @Test
    fun forecastStartsAtNextHourAndKeepsTwentyFourHoursAcrossMidnight() {
        val times = (0..47).map {
            "2026-11-${22 + it / 24}T${(it % 24).toString().padStart(2, '0')}:00"
        }
        val hourly = JSONObject().put("time", JSONArray(times))
            .put("temperature_2m", JSONArray(times.map { 23.5 }))
            .put("precipitation_probability", JSONArray(times.map { 35 }))
        val dates = (22..29).map { "2026-11-$it" }
        val forecast = WeatherStore.parseForecast(JSONObject()
            .put("current", JSONObject().put("time", "2026-11-22T10:30").put("temperature_2m", 19.9))
            .put("hourly", hourly)
            .put("daily", JSONObject().put("time", JSONArray(dates))))

        assertEquals(20, forecast.current.temperature)
        assertEquals(24, forecast.hours.size)
        assertEquals("2026-11-22T11:00", forecast.hours.first().time)
        assertEquals("2026-11-23T10:00", forecast.hours.last().time)
        assertEquals(35, forecast.hours.first().rain)
        assertEquals(7, forecast.days.size)
        assertNull(forecast.days.first().high)
    }

    @Test
    fun selectsNearestUsableStationAndRejectsStaleObservations() {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Taipei")
        }.parse("2026-11-22 10:20")!!.time
        fun station(name: String, lat: Double, temperature: Double, time: String) =
            JSONObject().put("StationName", name)
                .put("ObsTime", JSONObject().put("DateTime", "2026-11-22T$time:00+08:00"))
                .put("GeoInfo", JSONObject().put("Coordinates", JSONArray().put(JSONObject()
                    .put("CoordinateName", "WGS84").put("StationLatitude", lat).put("StationLongitude", 121.519))))
                .put("WeatherElement", JSONObject().put("AirTemperature", temperature))
        fun payload(vararg stations: JSONObject) =
            JSONObject().put("records", JSONObject().put("Station", JSONArray(stations.toList())))

        val stale = station("舊資料", 25.032, 23.0, "08:00")
        val invalid = station("缺值", 25.033, -99.0, "10:00")
        val nearby = station("臺北", 25.036, 24.2, "10:10")
        val farther = station("較遠", 25.114, 20.0, "10:10")

        assertNull(WeatherStore.parseStation(payload(stale, invalid), weatherPlaces.first(), now))
        val observed = WeatherStore.parseStation(payload(stale, invalid, farther, nearby), weatherPlaces.first(), now)
        assertEquals("臺北", observed?.station)
        assertEquals(24, observed?.temperature)
    }
}
