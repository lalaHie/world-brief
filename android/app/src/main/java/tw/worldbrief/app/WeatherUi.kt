package tw.worldbrief.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun HomeWeatherCard(place: WeatherPlace, report: WeatherReport?, onOpen: () -> Unit) {
    val forecast = report?.forecast
    val current = forecast?.current
    val station = report?.station
    val day = forecast?.days?.firstOrNull()
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            .clickable(role = Role.Button, onClickLabel = "開啟完整預報", onClick = onOpen),
        shape = RoundedCornerShape(18.dp), color = Color(0xFF075A4F), contentColor = Color.White
    ) {
        Column(Modifier.background(Brush.verticalGradient(listOf(Color(0xFF16776A), Color(0xFF05433B))))
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(place.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text("完整預報 ›", fontSize = 13.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(degrees(station?.temperature ?: current?.temperature), fontSize = 36.sp,
                    lineHeight = 42.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Column {
                    Text("${weatherSymbol(current?.code, current?.isDay != false)} ${weatherLabel(current?.code)}", fontWeight = FontWeight.SemiBold)
                    if (day != null && !report.cached) Text("低 ${degrees(day.low)} ／ 高 ${degrees(day.high)}", fontSize = 13.sp)
                }
            }
            Text(weatherSource(report), fontSize = 11.sp, color = Color(0xFFE2F1EA))
            if (forecast != null && !report.cached) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.28f))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
    Scaffold(topBar = {
        TopAppBar(title = { Text("天氣") },
            navigationIcon = { TextButton(onClick = onBack) { Text("‹ 返回") } },
            actions = { TextButton(onClick = onRefresh) { Text("更新") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { locations = true }) { Text("${place.name} ▾") }
                    TextButton(onClick = { configure = true }) { Text("實況設定") }
                }
                val current = forecast?.current
                Text("${weatherSymbol(current?.code, current?.isDay != false)}  ${degrees(report?.station?.temperature ?: current?.temperature)}  ${weatherLabel(current?.code)}",
                    fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text(weatherSource(report), fontSize = 12.sp)
                forecast?.days?.firstOrNull()?.let {
                    val label = if (report.cached) it.date else "今日"
                    Text("$label 低 ${degrees(it.low)} ／ 高 ${degrees(it.high)}", fontSize = 13.sp)
                }
            }
            if (forecast != null) {
                if (forecast.hours.isNotEmpty() && !report.cached) item {
                    Text("逐時預報 · 未來 24 小時", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(forecast.hours, key = { it.time }) {
                            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Box(Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                                    WeatherHourCell(it.time.takeLast(5), it.code, it.temperature, it.rain, it.isDay)
                                }
                            }
                        }
                    }
                }
                if (forecast.days.isNotEmpty()) {
                    item { Text("一週預報", style = MaterialTheme.typography.titleLarge) }
                    items(forecast.days, key = { it.date }) { day ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(weatherDateLabel(day.date), Modifier.weight(0.8f), fontWeight = FontWeight.Medium)
                            Text("${weatherSymbol(day.code)} ${weatherLabel(day.code)}", Modifier.weight(1.45f), fontSize = 13.sp)
                            Text(rainLabel(day.rain), Modifier.weight(0.6f), fontSize = 13.sp)
                            Text("${degrees(day.low)} / ${degrees(day.high)}", Modifier.weight(1f), fontSize = 13.sp)
                        }
                        HorizontalDivider()
                    }
                }
                item {
                    TextButton(onClick = { links.openUri("https://open-meteo.com/") }) { Text("預報與天氣圖示：Open‑Meteo ↗") }
                }
            }
        }
    }
}

@Composable
private fun WeatherHourCell(time: String, code: Int?, temperature: Int?, rain: Int?, isDay: Boolean) {
    Column(Modifier.widthIn(min = 42.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(time, fontSize = 11.sp)
        Text(weatherSymbol(code, isDay), fontSize = 19.sp)
        Text(degrees(temperature), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        if (rain != null) Text(rainLabel(rain), fontSize = 11.sp)
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
