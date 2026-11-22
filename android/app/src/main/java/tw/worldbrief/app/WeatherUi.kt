package tw.worldbrief.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun HomeWeatherCard(place: WeatherPlace, report: WeatherReport?, onOpen: () -> Unit) {
    val forecast = report?.forecast
    val current = forecast?.current
    val station = report?.station
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            .clickable(role = Role.Button, onClickLabel = "開啟完整預報", onClick = onOpen),
        shape = RoundedCornerShape(18.dp), color = Color(0xFF075A4F), contentColor = Color.White
    ) {
        Column(Modifier.background(Brush.verticalGradient(listOf(Color(0xFF16776A), Color(0xFF05433B))))
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(place.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text("完整預報 ›", fontSize = 13.sp)
            }
            WeatherSummary(report, modifier = Modifier.fillMaxWidth(), fontSize = 44)
            Text(weatherSource(report), fontSize = 11.sp, color = Color(0xFFE2F1EA))
            if (forecast != null && !report.cached) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.28f))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { WeatherHourCell("現在", current?.code, station?.temperature ?: current?.temperature, null, current?.isDay != false) }
                    items(forecast.hours.take(5), key = { it.time }) {
                        WeatherHourCell(it.time.takeLast(5), it.code, it.temperature, null, it.isDay)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WeatherDetails(
    place: WeatherPlace,
    report: WeatherReport?,
    onPlace: (WeatherPlace) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val links = LocalUriHandler.current
    var locations by remember { mutableStateOf(false) }
    var configure by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    if (locations) {
        AlertDialog(onDismissRequest = { locations = false }, title = { Text("選擇預報地點") },
            text = {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(weatherPlaces, key = { it.name }) { option ->
                        TextButton(onClick = { onPlace(option); locations = false }, modifier = Modifier.fillMaxWidth()) {
                            Text(option.name)
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { locations = false }) { Text("關閉") } })
    }
    if (configure) {
        AlertDialog(onDismissRequest = { configure = false; key = "" }, title = { Text("中央氣象署測站實況") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("輸入中央氣象署開放資料授權碼，即可查詢附近測站。未設定時使用 Open‑Meteo 的目前氣溫估計。")
                    OutlinedTextField(key, { key = it; error = false }, label = { Text("氣象署授權碼") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, isError = error)
                    if (error) Text("無法儲存授權碼", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { links.openUri("https://opendata.cwa.gov.tw/") }) { Text("前往氣象資料開放平台 ↗") }
                    if (WeatherStore.hasStationKey(context)) TextButton(onClick = {
                        WeatherStore.saveStationKey(context, "")
                        configure = false; key = ""; onRefresh()
                    }) { Text("移除授權碼") }
                }
            }, confirmButton = {
                TextButton(enabled = key.isNotBlank(), onClick = {
                    runCatching { WeatherStore.saveStationKey(context, key) }.onSuccess {
                        key = ""; configure = false; onRefresh()
                    }.onFailure { error = true }
                }) { Text("儲存") }
            }, dismissButton = {
                TextButton(onClick = { configure = false; key = "" }) { Text("取消") }
            })
    }
    val forecast = report?.forecast
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.4f
    val summaryBackground = if (dark) Color(0xFF29443A) else Color(0xFFE5EDD9)
    val tileBackground = if (dark) Color(0xFF243B34) else Color(0xFFEAF0E2)
    val rainColor = if (dark) Color(0xFF9DCEFF) else Color(0xFF07599D)
    val days = forecast?.days.orEmpty()
    val weekMinimum = days.mapNotNull { it.low }.minOrNull()
    val weekMaximum = days.mapNotNull { it.high }.maxOrNull()
    Scaffold(containerColor = colors.background, topBar = {
        TopAppBar(
            title = { Text("天氣", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            },
            actions = {
                OutlinedButton(onClick = { locations = true },
                    modifier = Modifier.widthIn(max = 175.dp).padding(end = 12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text("${place.name} ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background)
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Surface(shape = RoundedCornerShape(14.dp), color = summaryBackground, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        WeatherSummary(report, modifier = Modifier.fillMaxWidth(), fontSize = 40)
                        Text(weatherSource(report), fontSize = 11.sp, color = colors.onSurfaceVariant)
                    }
                }
            }
            if (forecast != null && forecast.hours.isNotEmpty() && report.cached == false) item {
                WeatherSectionTitle("逐時預報", "未來 24 小時")
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(forecast.hours, key = { it.time }) { hour ->
                        Surface(shape = RoundedCornerShape(10.dp), color = tileBackground) {
                            Box(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                WeatherHourCell(hour.time.takeLast(5), hour.code, hour.temperature, hour.rain, hour.isDay,
                                    showRain = true, rainColor = rainColor)
                            }
                        }
                    }
                }
            }
            if (days.isNotEmpty()) {
                item { WeatherSectionTitle("一週預報") }
                items(days, key = { it.date }) { day ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (day == days.first() && report?.cached == false) "今天" else weatherDateLabel(day.date),
                            Modifier.weight(0.79f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(weatherSymbol(day.code), Modifier.weight(0.62f), fontSize = 19.sp)
                        Text(if (day.rain == null) "—" else "💧${rainLabel(day.rain)}",
                            Modifier.weight(1f), fontSize = 13.sp, color = rainColor)
                        Text(degrees(day.low), Modifier.weight(0.59f), fontSize = 14.sp)
                        WeeklyTemperatureRange(day, weekMinimum, weekMaximum,
                            modifier = Modifier.weight(1.25f).height(10.dp))
                        Text(degrees(day.high), Modifier.weight(0.59f), fontSize = 14.sp)
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { configure = true }) { Text("實況設定") }
                    TextButton(onClick = onRefresh) { Text("更新預報") }
                }
                if (forecast != null) TextButton(onClick = { links.openUri("https://open-meteo.com/") }) {
                    Text("預報來源：Open‑Meteo ↗")
                }
            }
        }
    }
}

@Composable
private fun WeatherSummary(report: WeatherReport?, modifier: Modifier, fontSize: Int) {
    val forecast = report?.forecast
    val current = forecast?.current
    val temperature = report?.station?.temperature ?: current?.temperature
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(temperature?.let { "$it°C" } ?: "—", fontSize = fontSize.sp, lineHeight = (fontSize + 6).sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(weatherSymbol(current?.code, current?.isDay != false), fontSize = 27.sp)
        Column {
            Text(weatherLabel(current?.code), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            forecast?.days?.firstOrNull()?.takeUnless { report.cached }?.let {
                Text("低 ${degrees(it.low)} ／ 高 ${degrees(it.high)}", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun WeatherSectionTitle(title: String, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = RoundedCornerShape(3.dp), color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(4.dp).height(18.dp)) {}
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        subtitle?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun WeeklyTemperatureRange(day: WeatherDay, minimum: Int?, maximum: Int?, modifier: Modifier) {
    val base = MaterialTheme.colorScheme.primary
    Canvas(modifier.clearAndSetSemantics {}) {
        if (day.low != null && day.high != null && day.high >= day.low && minimum != null && maximum != null) {
            val start = size.height / 2
            val span = (maximum - minimum).coerceAtLeast(1)
            val width = (size.width - size.height).coerceAtLeast(0f)
            val left = start + (day.low - minimum).toFloat() / span * width
            val right = start + (day.high - minimum).toFloat() / span * width
            drawLine(base.copy(alpha = 0.12f), Offset(start, center.y), Offset(start + width, center.y),
                strokeWidth = size.height, cap = StrokeCap.Round)
            drawLine(base.copy(alpha = 0.64f), Offset(left, center.y), Offset(right, center.y),
                strokeWidth = size.height, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun WeatherHourCell(
    time: String, code: Int?, temperature: Int?, rain: Int?, isDay: Boolean,
    showRain: Boolean = false, rainColor: Color = Color.Unspecified
) {
    Column(Modifier.widthIn(min = 42.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(time, fontSize = 11.sp)
        Text(weatherSymbol(code, isDay), fontSize = 19.sp)
        Text(degrees(temperature), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        if (showRain) Text(if (rain == null) "—" else "💧${rainLabel(rain)}", fontSize = 11.sp, color = rainColor)
    }
}

private fun weatherSource(report: WeatherReport?): String {
    if (report == null) return "正在讀取天氣…"
    val observation = report.station?.let { "實測氣溫 · 氣象署 ${it.station} ${taipeiTimestamp(it.observedAt).takeLast(5)}" }
    val forecast = report.forecast?.let {
        val time = it.current.time.replace('T', ' ')
        if (report.cached) "上次預報 · Open‑Meteo $time" else "氣溫估計 · Open‑Meteo ${time.takeLast(5)}"
    }
    return when {
        observation != null && report.cached -> "$observation\n$forecast"
        else -> observation ?: forecast ?: "暫時無法取得天氣，請稍後更新"
    }
}

private fun degrees(value: Int?) = value?.let { "$it°" } ?: "—"
private fun rainLabel(value: Int?) = value?.let { "$it%" } ?: "—"

private fun weatherLabel(code: Int?) = when (code) {
    0 -> "晴朗"
    1, 2 -> "晴時多雲"
    3 -> "陰天"
    45, 48 -> "有霧"
    in 51..67, in 80..82 -> "有雨"
    in 71..77, 85, 86 -> "有雪"
    in 95..99 -> "雷雨"
    else -> "天氣資料不足"
}

private fun weatherSymbol(code: Int?, isDay: Boolean = true) = when (code) {
    0 -> if (isDay) "☀️" else "🌙"
    1, 2 -> if (isDay) "⛅" else "☁️"
    3 -> "☁️"
    45, 48 -> "🌫️"
    in 51..67, in 80..82 -> "🌧️"
    in 71..77, 85, 86 -> "🌨️"
    in 95..99 -> "⛈️"
    else -> "—"
}
