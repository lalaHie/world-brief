package tw.worldbrief.app

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.hypot
import kotlin.math.roundToInt

internal data class WeatherPlace(val name: String, val lat: Double, val lon: Double)

internal val weatherPlaces = listOf(
    WeatherPlace("臺北市・中正區", 25.032, 121.519),
    WeatherPlace("新北市・板橋區", 25.012, 121.464),
    WeatherPlace("基隆市・中正區", 25.138, 121.768),
    WeatherPlace("桃園市・桃園區", 24.993, 121.301),
    WeatherPlace("新竹市・東區", 24.800, 120.982),
    WeatherPlace("新竹縣・竹北市", 24.839, 121.010),
    WeatherPlace("苗栗縣・苗栗市", 24.564, 120.822),
    WeatherPlace("臺中市・西屯區", 24.164, 120.647),
    WeatherPlace("彰化縣・彰化市", 24.077, 120.542),
    WeatherPlace("南投縣・南投市", 23.913, 120.683),
    WeatherPlace("雲林縣・斗六市", 23.711, 120.542),
    WeatherPlace("嘉義市・東區", 23.480, 120.457),
    WeatherPlace("嘉義縣・太保市", 23.458, 120.294),
    WeatherPlace("臺南市・安平區", 22.992, 120.185),
    WeatherPlace("高雄市・苓雅區", 22.621, 120.312),
    WeatherPlace("屏東縣・屏東市", 22.673, 120.488),
    WeatherPlace("宜蘭縣・宜蘭市", 24.754, 121.753),
    WeatherPlace("花蓮縣・花蓮市", 23.984, 121.606),
    WeatherPlace("臺東縣・臺東市", 22.755, 121.150),
    WeatherPlace("澎湖縣・馬公市", 23.566, 119.579),
    WeatherPlace("金門縣・金城鎮", 24.433, 118.317),
    WeatherPlace("連江縣・南竿鄉", 26.154, 119.949)
)

internal data class WeatherHour(val time: String, val temperature: Int?, val code: Int?, val rain: Int?, val isDay: Boolean = true)
internal data class WeatherDay(val date: String, val low: Int?, val high: Int?, val code: Int?, val rain: Int?)
internal data class StationWeather(val temperature: Int, val station: String, val observedAt: Long)
internal data class WeatherForecast(val current: WeatherHour, val hours: List<WeatherHour>, val days: List<WeatherDay>)
internal data class WeatherReport(val forecast: WeatherForecast?, val station: StationWeather?, val cached: Boolean)

internal object WeatherStore {
    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"
    private const val STATIONS = "https://opendata.cwa.gov.tw/api/v1/rest/datastore/O-A0003-001"
    private const val MAX_AGE = 30 * 60 * 1000L
    private val stationKey = DeviceSecret("weather-private", "worldbrief-weather-key")

    fun hasStationKey(context: Context) = stationKey.configured(context)
    fun saveStationKey(context: Context, value: String) = stationKey.save(context, value)

    private fun cache(context: Context, place: WeatherPlace) = AtomicFile(
        File(context.cacheDir, "weather-${weatherPlaces.indexOf(place)}.json")
    )

    suspend fun load(context: Context, place: WeatherPlace, force: Boolean): WeatherReport =
        withContext(Dispatchers.IO) {
            val file = cache(context, place)
            val stored = readCache(file)
            val now = System.currentTimeMillis()
            val key = runCatching { stationKey.read(context) }.getOrNull()
            supervisorScope {
                val station = async {
                    key?.let {
                        attempt {
                            val token = URLEncoder.encode(it, "UTF-8")
                            val county = URLEncoder.encode(place.name.substringBefore('・'), "UTF-8")
                            parseStation(get("$STATIONS?Authorization=$token&CountyName=$county"), place, now)
                        }
                    }
                }
                val recent = stored != null && now - stored.optLong("savedAt") in 0 until MAX_AGE
                val active = if (recent && !force) null else attempt {
                    get("$FORECAST?latitude=${place.lat}&longitude=${place.lon}&current=temperature_2m%2Cweather_code%2Cis_day" +
                        "&hourly=temperature_2m%2Cweather_code%2Cprecipitation_probability%2Cis_day" +
                        "&daily=temperature_2m_min%2Ctemperature_2m_max%2Cweather_code%2Cprecipitation_probability_max" +
                        "&timezone=Asia%2FTaipei&forecast_days=7").also { response ->
                        parseForecast(response)
                        currentCoroutineContext().ensureActive()
                        response.put("savedAt", now)
                        writeCache(file, response)
                    }
                }
                val forecast = (active ?: stored)?.let { payload -> runCatching { parseForecast(payload) }.getOrNull() }
                WeatherReport(forecast, station.await(), active == null && !recent && forecast != null)
            }
        }

    @Synchronized
    private fun readCache(file: AtomicFile): JSONObject? =
        runCatching { file.openRead().bufferedReader().use { JSONObject(it.readText()) } }.getOrNull()

    @Synchronized
    private fun writeCache(file: AtomicFile, response: JSONObject) {
        runCatching {
            val out = file.startWrite()
            try {
                out.write(response.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                throw e
            }
        }
    }

    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun get(address: String): JSONObject {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false
        try {
            check(connection.responseCode in 200..299)
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseForecast(root: JSONObject): WeatherForecast {
        val current = root.getJSONObject("current")
        val time = current.getString("time")
        val hour = time.take(13)
        val hourly = root.getJSONObject("hourly")
        val hours = hourly.getJSONArray("time")
        val daily = root.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        fun number(group: JSONObject, field: String, index: Int): Int? =
            group.optJSONArray(field)?.optDouble(index)?.takeIf { it.isFinite() }?.roundToInt()

        return WeatherForecast(
            WeatherHour(time, current.optDouble("temperature_2m").takeIf { it.isFinite() }?.roundToInt(),
                current.optInt("weather_code", -1).takeIf { it >= 0 }, null, current.optInt("is_day", 1) == 1),
            (0 until hours.length()).asSequence().filter { hours.getString(it).take(13) > hour }.take(24).map {
                WeatherHour(hours.getString(it), number(hourly, "temperature_2m", it),
                    number(hourly, "weather_code", it), number(hourly, "precipitation_probability", it),
                    number(hourly, "is_day", it) != 0)
            }.toList(),
            (0 until dates.length()).asSequence().filter { dates.getString(it) >= time.take(10) }.take(7).map {
                WeatherDay(dates.getString(it), number(daily, "temperature_2m_min", it),
                    number(daily, "temperature_2m_max", it), number(daily, "weather_code", it),
                    number(daily, "precipitation_probability_max", it))
            }.toList()
        )
    }

    internal fun parseStation(root: JSONObject, place: WeatherPlace, now: Long): StationWeather? {
        val stations = root.getJSONObject("records").getJSONArray("Station")
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Taipei")
            isLenient = false
        }
        return (0 until stations.length()).asSequence().mapNotNull { i ->
            val station = stations.optJSONObject(i) ?: return@mapNotNull null
            val geo = station.optJSONObject("GeoInfo") ?: return@mapNotNull null
            val coords = geo.optJSONArray("Coordinates") ?: return@mapNotNull null
            val point = (0 until coords.length()).asSequence().mapNotNull(coords::optJSONObject)
                .firstOrNull { it.optString("CoordinateName") == "WGS84" } ?: return@mapNotNull null
            val lat = point.optDouble("StationLatitude")
            val lon = point.optDouble("StationLongitude")
            if (!lat.isFinite() || !lon.isFinite()) return@mapNotNull null
            val distance = hypot((lat - place.lat) * 111.0, (lon - place.lon) * 101.0)
            if (distance > 30) return@mapNotNull null
            val weather = station.optJSONObject("WeatherElement") ?: return@mapNotNull null
            val temperature = weather.optDouble("AirTemperature")
            if (temperature !in -40.0..55.0) return@mapNotNull null
            val timestamp = station.optJSONObject("ObsTime")?.optString("DateTime").orEmpty()
            val observed = runCatching { dateFormat.parse(timestamp.take(19))?.time }.getOrNull()
                ?: return@mapNotNull null
            if (now - observed !in -300_000L..3_600_000L) return@mapNotNull null
            val name = station.optString("StationName").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            distance to StationWeather(temperature.roundToInt(), name, observed)
        }.minByOrNull { it.first }?.second
    }
}

internal fun weatherDateLabel(date: String): String {
    val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Taipei") }
    val label = SimpleDateFormat("EE", Locale.TAIWAN).apply { timeZone = parser.timeZone }
    return runCatching { parser.parse(date)?.let(label::format) }.getOrNull() ?: date
}
