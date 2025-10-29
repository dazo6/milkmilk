package com.dazo66.milkmilk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dazo66.milkmilk.DailyBehaviorStats
import com.dazo66.milkmilk.MainViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale

/**
 * 热力图组件，支持 日/周/月/年 四种视图；按年分隔并展示年度总次数。
 */
// 统一热力图方块尺寸与间距（缩小并保持一致）
private val HEATMAP_CELL_SIZE = 18.dp
private val HEATMAP_GAP = 5.dp

// 并排两个矩阵单元之间的间距（行内两个单元的水平间距）
private val HEATMAP_MATRIX_GAP = 15.dp

// 方块内数字的统一字体大小
private val HEATMAP_TEXT_SIZE = 12.sp

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
            val monthPairs = months.chunked(2)
            monthPairs.forEach { pair ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(HEATMAP_MATRIX_GAP)
                ) {
                    pair.forEach { month ->
                        val matrix = buildMonthMatrix(year, month)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = SimpleDateFormat("yyyy年MM月", Locale.getDefault()).format(
                                    GregorianCalendar(year, month, 1).time
                                ),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(HEATMAP_GAP)) {
                                matrix.forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(HEATMAP_GAP)) {
                                        row.forEach { date ->
                                            val isPastOrToday = date != null && !date.after(today)
                                            if (date != null && isPastOrToday) {
                                                val normalized = normalizeDay(date)
                                                val count =
                                                    statsByDate[normalized]?.behaviorCount ?: 0
                                                DaySquare(
                                                    date = date,
                                                    count = count,
                                                    onClick = { onDayClick(date, count) },
                                                    sizeDp = HEATMAP_CELL_SIZE
                                                )
                                            } else {
                                                InvisibleSquare(sizeDp = HEATMAP_CELL_SIZE)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (pair.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
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
    val years =
        remember(dailyStats) { dailyStats.map { getYear(it.date) }.distinct().sortedDescending() }

    val yearPairs = remember(years) { years.chunked(2) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        yearPairs.forEach { pair ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(HEATMAP_MATRIX_GAP)
            ) {
                pair.forEach { year ->
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

                    Column(modifier = Modifier.weight(1f)) {
                        YearSeparator(year = year, total = yearTotal)
                        Column(verticalArrangement = Arrangement.spacedBy(HEATMAP_GAP)) {
                            for (r in 0 until rows) {
                                Row(horizontalArrangement = Arrangement.spacedBy(HEATMAP_GAP)) {
                                    for (c in 0 until cols) {
                                        val index = r * cols + c + 1
                                        if (index in 1..totalWeeks) {
                                            val count = weekCounts[index]
                                            AggregatedSquare(
                                                count = count,
                                                sizeDp = HEATMAP_CELL_SIZE,
                                                onClick = { onWeekClick(year, index) })
                                        } else {
                                            InvisibleSquare(sizeDp = HEATMAP_CELL_SIZE)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (pair.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ————————————————— 月视图：每年一个矩阵，按月聚合 —————————————————
@Composable
private fun MonthHeatmap(dailyStats: List<DailyBehaviorStats>, onMonthClick: (Int, Int) -> Unit) {
    val years =
        remember(dailyStats) { dailyStats.map { getYear(it.date) }.distinct().sortedDescending() }
    // 使用“今天”作为上限：过去月份即使无数据也显示并可点击
    val today = remember { Calendar.getInstance() }
    val currentYear = today.get(Calendar.YEAR)
    val currentMonth = today.get(Calendar.MONTH) // 0-based

    val yearPairs = remember(years) { years.chunked(2) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        yearPairs.forEach { pair ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(HEATMAP_MATRIX_GAP)
            ) {
                pair.forEach { year ->
                    val yearStats = dailyStats.filter { getYear(it.date) == year }
                    val yearTotal = yearStats.sumOf { it.behaviorCount }

                    val monthCounts = IntArray(12) { 0 }.also { arr ->
                        yearStats.forEach { stat ->
                            val month = getMonth(stat.date)
                            arr[month] += stat.behaviorCount
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        YearSeparator(year = year, total = yearTotal)
                        val cols = 7
                        val rows = 2
                        Column(verticalArrangement = Arrangement.spacedBy(HEATMAP_GAP)) {
                            for (r in 0 until rows) {
                                Row(horizontalArrangement = Arrangement.spacedBy(HEATMAP_GAP)) {
                                    for (c in 0 until cols) {
                                        val monthIndex = r * cols + c
                                        if (monthIndex < 12) {
                                            val isPastOrCurrentMonth =
                                                (year < currentYear) || (year == currentYear && monthIndex <= currentMonth)
                                            if (isPastOrCurrentMonth) {
                                                val count = monthCounts[monthIndex]
                                                AggregatedSquare(
                                                    count = count,
                                                    sizeDp = HEATMAP_CELL_SIZE,
                                                    onClick = { onMonthClick(year, monthIndex) })
                                            } else {
                                                InvisibleSquare(sizeDp = HEATMAP_CELL_SIZE)
                                            }
                                        } else {
                                            InvisibleSquare(sizeDp = HEATMAP_CELL_SIZE)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (pair.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ————————————————— 年视图：仅展示年度总次数（单行） —————————————————
@Composable
private fun YearHeatmap(dailyStats: List<DailyBehaviorStats>) {
    val years =
        remember(dailyStats) { dailyStats.map { getYear(it.date) }.distinct().sortedDescending() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        years.forEach { year ->
            val yearTotal =
                dailyStats.filter { getYear(it.date) == year }.sumOf { it.behaviorCount }
            YearSeparator(year = year, total = yearTotal)
        }
    }
}

@Composable
private fun YearSeparator(year: Int, total: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
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
                fontSize = HEATMAP_TEXT_SIZE,
                modifier = Modifier
                    .wrapContentSize(Alignment.Center)
                    .offset(y = (-2).dp),
                textAlign = TextAlign.Center,
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
                fontSize = HEATMAP_TEXT_SIZE,
                modifier = Modifier
                    .wrapContentSize(Alignment.Center)
                    .offset(y = (-2).dp),
                textAlign = TextAlign.Center,
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
