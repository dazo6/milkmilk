package com.dazo66.milkmilk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dazo66.milkmilk.DailyBehaviorStats
import com.dazo66.milkmilk.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * 热力图组件，支持 日/周/月/年 四种视图；按年分隔并展示年度总次数。
 */
@Composable
fun HeatmapView(
    dailyStats: List<DailyBehaviorStats>,
    viewType: MainViewModel.StatisticsViewType,
    onDayClick: (Date, Int) -> Unit,
    modifier: Modifier = Modifier,
    onWeekClick: (Int, Int) -> Unit = { _, _ -> },
    onMonthClick: (Int, Int) -> Unit = { _, _ -> }
) {
    Column(modifier = modifier) {
        /*Text(
            text = when (viewType) {
                MainViewModel.StatisticsViewType.DAY -> "使用行为热力图（日视图）"
                MainViewModel.StatisticsViewType.WEEK -> "使用行为热力图（周视图）"
                MainViewModel.StatisticsViewType.MONTH -> "使用行为热力图（月视图）"
                MainViewModel.StatisticsViewType.YEAR -> "使用行为热力图（年视图）"
            },
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )*/

        when (viewType) {
            MainViewModel.StatisticsViewType.DAY -> DayHeatmap(dailyStats, onDayClick)
            MainViewModel.StatisticsViewType.WEEK -> WeekHeatmap(dailyStats, onWeekClick)
            MainViewModel.StatisticsViewType.MONTH -> MonthHeatmap(dailyStats, onMonthClick)
            MainViewModel.StatisticsViewType.YEAR -> YearHeatmap(dailyStats)
        }
    }
}

// ————————————————— 日视图：每月一个矩阵，按年分隔，倒序排列 —————————————————
@Composable
private fun DayHeatmap(
    dailyStats: List<DailyBehaviorStats>,
    onDayClick: (Date, Int) -> Unit
) {
    val statsByDate = remember(dailyStats) { dailyStats.associateBy { normalizeDay(it.date) } }
    val years = remember(dailyStats) {
        dailyStats.map { getYear(it.date) }.distinct().sortedDescending()
    }
    // 以“今天”为范围上限；过去日期即使无数据也显示并可点击
    val today = remember { normalizeDay(Date()) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        years.forEach { year ->
            val yearStats = dailyStats.filter { getYear(it.date) == year }
            val yearTotal = yearStats.sumOf { it.behaviorCount }

            YearSeparator(year = year, total = yearTotal)

            val months = yearStats.map { getMonth(it.date) }.distinct().sortedDescending()
            months.forEach { month ->
                val matrix = buildMonthMatrix(year, month)
                 Text(
                     text = SimpleDateFormat("yyyy年MM月", Locale.getDefault()).format(
                         GregorianCalendar(year, month, 1).time
                     ),
                     fontWeight = FontWeight.SemiBold,
                     fontSize = 14.sp,
                     modifier = Modifier.padding(vertical = 4.dp)
                 )
                 // 在日视图中：进一步增大间距、进一步缩小格子
                 BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                     val columns = 7
                     val gap = 8.dp
                     val rawSize = (maxWidth - gap * (columns - 1)) / columns
                     val cellSize = rawSize * 0.90f
                     Column(verticalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxWidth()) {
                         matrix.forEach { row ->
                             Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                 row.forEach { date ->
                                     // 过去或今天：显示可点击的方块；未来：占位不绘制
                                     val isPastOrToday = date != null && !date.after(today)
                                     if (date != null && isPastOrToday) {
                                         val normalized = normalizeDay(date)
                                         val count = statsByDate[normalized]?.behaviorCount ?: 0
                                        DaySquare(
                                            date = date,
                                            count = count,
                                            onClick = { onDayClick(date, count) },
                                            sizeDp = cellSize
                                        )
                                     } else {
                                         InvisibleSquare(sizeDp = cellSize)
                                     }
                                 }
                             }
                         }
                     }
                 }
            }
        }
    }
}

// ————————————————— 周视图：每年一个矩阵，按周聚合 —————————————————
@Composable
private fun WeekHeatmap(dailyStats: List<DailyBehaviorStats>, onWeekClick: (Int, Int) -> Unit) {
    val cal = remember { Calendar.getInstance() }
    val years = remember(dailyStats) { dailyStats.map { getYear(it.date) }.distinct().sortedDescending() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        years.forEach { year ->
            val yearStats = dailyStats.filter { getYear(it.date) == year }
            val yearTotal = yearStats.sumOf { it.behaviorCount }

            val weekCounts = IntArray(54) { 0 }.also { arr ->
                yearStats.forEach { stat ->
                    cal.time = stat.date
                    val week = cal.get(Calendar.WEEK_OF_YEAR)
                    if (week in 1..53) arr[week] += stat.behaviorCount
                }
            }
            val totalWeeks = (53 downTo 1).firstOrNull { weekCounts[it] > 0 } ?: 52
            val cols = 7
            val rows = (totalWeeks + cols - 1) / cols

            YearSeparator(year = year, total = yearTotal)

            BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                val cols = 7
                val gap = 8.dp
                val rawSize = (maxWidth - gap * (cols - 1)) / cols
                val cellSize = rawSize * 0.90f
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    for (r in 0 until rows) {
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            for (c in 0 until cols) {
                                val index = r * cols + c + 1
                                if (index in 1..totalWeeks) {
                                    val count = weekCounts[index]
                                    AggregatedSquare(count = count, sizeDp = cellSize, onClick = { onWeekClick(year, index) })
                                } else {
                                    InvisibleSquare(sizeDp = cellSize)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ————————————————— 月视图：每年一个矩阵，按月聚合 —————————————————
@Composable
private fun MonthHeatmap(dailyStats: List<DailyBehaviorStats>, onMonthClick: (Int, Int) -> Unit) {
    val years = remember(dailyStats) { dailyStats.map { getYear(it.date) }.distinct().sortedDescending() }
    // 使用“今天”作为上限：过去月份即使无数据也显示并可点击
    val today = remember { Calendar.getInstance() }
    val currentYear = today.get(Calendar.YEAR)
    val currentMonth = today.get(Calendar.MONTH) // 0-based

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        years.forEach { year ->
            val yearStats = dailyStats.filter { getYear(it.date) == year }
            val yearTotal = yearStats.sumOf { it.behaviorCount }

            val monthCounts = IntArray(12) { 0 }.also { arr ->
                yearStats.forEach { stat ->
                    val month = getMonth(stat.date)
                    arr[month] += stat.behaviorCount
                }
            }

            YearSeparator(year = year, total = yearTotal)

            BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                val cols = 7
                val rows = 2
                val gap = 8.dp
                val rawSize = (maxWidth - gap * (cols - 1)) / cols
                val cellSize = rawSize * 0.90f
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    for (r in 0 until rows) {
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            for (c in 0 until cols) {
                                val monthIndex = r * cols + c
                                if (monthIndex < 12) {
                                    val isPastOrCurrentMonth = (year < currentYear) || (year == currentYear && monthIndex <= currentMonth)
                                    if (isPastOrCurrentMonth) {
                                        val count = monthCounts[monthIndex]
                                        AggregatedSquare(count = count, sizeDp = cellSize, onClick = { onMonthClick(year, monthIndex) })
                                    } else {
                                        InvisibleSquare(sizeDp = cellSize)
                                    }
                                } else {
                                    InvisibleSquare(sizeDp = cellSize)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ————————————————— 年视图：仅展示年度总次数（单行） —————————————————
@Composable
private fun YearHeatmap(dailyStats: List<DailyBehaviorStats>) {
    val years = remember(dailyStats) { dailyStats.map { getYear(it.date) }.distinct().sortedDescending() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        years.forEach { year ->
            val yearTotal = dailyStats.filter { getYear(it.date) == year }.sumOf { it.behaviorCount }
            YearSeparator(year = year, total = yearTotal)
        }
    }
}

@Composable
private fun YearSeparator(year: Int, total: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "${year}年 ${total}次",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptySquare(sizeDp: Dp) {
    Box(
        modifier = Modifier
            .size(sizeDp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xFFEBEDF0))
    )
}

// 不显示的占位（保持网格对齐但不绘制背景）
@Composable
private fun InvisibleSquare(sizeDp: Dp) {
    Spacer(modifier = Modifier.size(sizeDp))
}

// 调整 AggregatedSquare 内文字大小
@Composable
private fun AggregatedSquare(count: Int, sizeDp: Dp, onClick: (() -> Unit)? = null) {
    val color = getHeatmapColor(count)
    Box(
        modifier = Modifier
            .size(sizeDp)
            .clip(RoundedCornerShape(3.dp))
            .background(color)
            .let { base -> if (onClick != null) base.clickable { onClick() } else base },
        contentAlignment = Alignment.Center
    ) {
        if (count > 0) {
            Text(
                text = count.toString(),
                fontSize = 13.sp,
                color = if (count <= 2) Color.Black else Color.White
            )
        }
    }
}

@Composable
private fun DaySquare(
    date: Date,
    count: Int,
    onClick: () -> Unit,
    sizeDp: Dp
) {
    val color = getHeatmapColor(count)
    val textColor = if (count <= 2) Color.Black else Color.White

    Box(
        modifier = Modifier
            .size(sizeDp)
            .clip(RoundedCornerShape(3.dp))
            .background(color)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (count > 0) {
            Text(
                text = count.toString(),
                fontSize = 13.sp,
                color = textColor
            )
        }
    }
}

private fun getHeatmapColor(count: Int): Color {
    return when {
        count == 0 -> Color(0xFFEBEDF0)
        count <= 2 -> Color(0xFF9BE9A8)
        count <= 5 -> Color(0xFF40C463)
        count <= 10 -> Color(0xFF30A14E)
        else -> Color(0xFF216E39)
    }
}

// 构建某年某月的 7x6 网格（按周排列，周一开始）
private fun buildMonthMatrix(year: Int, month0Based: Int): List<List<Date?>> {
    val cal = GregorianCalendar(year, month0Based, 1).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDow = ((cal.get(Calendar.DAY_OF_WEEK) + 6) % 7) // 0=周一
    val cells = Array(6) { arrayOfNulls<Date>(7) }

    var day = 1
    var row = 0
    var col = firstDow
    while (day <= daysInMonth) {
        cal.set(Calendar.DAY_OF_MONTH, day)
        cells[row][col] = cal.time
        day++
        col++
        if (col == 7) {
            col = 0
            row++
            if (row == 6) break
        }
    }
    return cells.map { it.toList() }
}

private fun normalizeDay(date: Date): Date {
    val cal = Calendar.getInstance()
    cal.time = date
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.time
}

private fun getYear(date: Date): Int {
    val cal = Calendar.getInstance(); cal.time = date; return cal.get(Calendar.YEAR)
}

private fun getMonth(date: Date): Int {
    val cal = Calendar.getInstance(); cal.time = date; return cal.get(Calendar.MONTH)
}