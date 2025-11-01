@file:OptIn(ExperimentalMaterial3Api::class)

package com.dazo66.milkmilk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dazo66.milkmilk.ContinuousBehavior
import com.dazo66.milkmilk.DailyBehaviorStats
import com.dazo66.milkmilk.MainViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun StatisticsTab(viewModel: MainViewModel) {
    val dailyStats = viewModel.dailyBehaviorStats
    var selectedView by remember { mutableStateOf(viewModel.statisticsViewType) }

    // 只在监控应用列表变化时加载统计，避免重复初始加载
    LaunchedEffect(viewModel.monitoredApps.size) {
        viewModel.loadBehaviorStats()
    }

    // 刷新状态（仅用于点击刷新按钮）
    val refreshing = remember { mutableStateOf(false) }

    // 将所有内容放入同一个滚动容器，恢复上下滑动
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 概览统计卡片（含“年”）
        item {
            StatsOverviewCards(dailyStats)
        }

        // 视图选择器：日/周/月 + 手动刷新按钮
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedView == MainViewModel.StatisticsViewType.DAY,
                    onClick = {
                        selectedView = MainViewModel.StatisticsViewType.DAY
                        viewModel.statisticsViewType = MainViewModel.StatisticsViewType.DAY
                    },
                    label = { Text("日视图") }
                )
                FilterChip(
                    selected = selectedView == MainViewModel.StatisticsViewType.WEEK,
                    onClick = {
                        selectedView = MainViewModel.StatisticsViewType.WEEK
                        viewModel.statisticsViewType = MainViewModel.StatisticsViewType.WEEK
                    },
                    label = { Text("周视图") }
                )
                FilterChip(
                    selected = selectedView == MainViewModel.StatisticsViewType.MONTH,
                    onClick = {
                        selectedView = MainViewModel.StatisticsViewType.MONTH
                        viewModel.statisticsViewType = MainViewModel.StatisticsViewType.MONTH
                    },
                    label = { Text("月视图") }
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    refreshing.value = true
                    viewModel.manualIncrementalRefresh { refreshing.value = false }
                }) {
                    Icon(imageVector = Icons.Filled.Refresh, contentDescription = "刷新")
                }
            }
        }

        // 热力图展示
        item {
            HeatmapView(
                dailyStats = viewModel.dailyBehaviorStats,
                viewType = viewModel.statisticsViewType,
                onDayClick = viewModel::onDayClick,
                onWeekClick = viewModel::onWeekClick,
                onMonthClick = viewModel::onMonthClick,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // 详情对话框放在滚动容器之外，避免影响测量
    if (viewModel.showDayDetailDialog && viewModel.selectedDate != null) {
        DayDetailDialog(
            date = viewModel.selectedDate!!,
            count = viewModel.selectedDayCount,
            behaviors = viewModel.selectedDayBehaviors,
            onDismiss = { viewModel.showDayDetailDialog = false },
            timeFormatter = remember {
                SimpleDateFormat(
                    "HH:mm:ss",
                    Locale.getDefault()
                )
            },
            dateFormatter = remember {
                SimpleDateFormat(
                    "yyyy-MM-dd",
                    Locale.getDefault()
                )
            }
        )
    }

    if (viewModel.showWeekDetailDialog && viewModel.selectedWeek != null) {
        val (year, week) = viewModel.selectedWeek!!
        RangeDetailDialog(
            title = "${year}年 第${week}周 行为详情（${viewModel.selectedRangeBehaviors.size}条）",
            behaviors = viewModel.selectedRangeBehaviors,
            onDismiss = { viewModel.showWeekDetailDialog = false },
            timeFormatter = remember {
                SimpleDateFormat(
                    "HH:mm:ss",
                    Locale.getDefault()
                )
            },
            dateFormatter = remember {
                SimpleDateFormat(
                    "yyyy-MM-dd",
                    Locale.getDefault()
                )
            }
        )
    }

    if (viewModel.showMonthDetailDialog && viewModel.selectedMonth != null) {
        val (year, month0) = viewModel.selectedMonth!!
        RangeDetailDialog(
            title = "${year}年${month0 + 1}月 行为详情（${viewModel.selectedRangeBehaviors.size}条）",
            behaviors = viewModel.selectedRangeBehaviors,
            onDismiss = { viewModel.showMonthDetailDialog = false },
            timeFormatter = remember {
                SimpleDateFormat(
                    "HH:mm:ss",
                    Locale.getDefault()
                )
            },
            dateFormatter = remember {
                SimpleDateFormat(
                    "yyyy-MM-dd",
                    Locale.getDefault()
                )
            }
        )
    }
}


@Composable
fun StatCard(title: String, count: Int, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(count.toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun StatsOverviewCards(dailyStats: List<DailyBehaviorStats>) {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.time

    val cal = Calendar.getInstance()
    val weekStart = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.time

    val monthStart = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.time

    val yearStart = Calendar.getInstance().apply {
        set(Calendar.MONTH, Calendar.JANUARY)
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.time

    val todayCount = dailyStats.firstOrNull { isSameDay(it.date, today) }?.behaviorCount ?: 0
    val weekCount = dailyStats.filter { it.date >= weekStart }.sumOf { it.behaviorCount }
    val monthCount = dailyStats.filter { it.date >= monthStart }.sumOf { it.behaviorCount }
    val yearCount = dailyStats.filter { it.date >= yearStart }.sumOf { it.behaviorCount }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard(title = "今天次数", count = todayCount, modifier = Modifier.weight(1f))
            StatCard(title = "本周次数", count = weekCount, modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard(title = "本月次数", count = monthCount, modifier = Modifier.weight(1f))
            StatCard(title = "本年次数", count = yearCount, modifier = Modifier.weight(1f))
        }
    }
}

fun isSameDay(a: Date, b: Date): Boolean {
    val ca = Calendar.getInstance().apply { time = a }
    val cb = Calendar.getInstance().apply { time = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

@Composable
fun DayDetailDialog(
    date: Date,
    count: Int,
    onDismiss: () -> Unit,
    behaviors: List<ContinuousBehavior>,
    timeFormatter: SimpleDateFormat,
    dateFormatter: SimpleDateFormat
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${dateFormatter.format(date)} 行为详情（${count}条）") },
        text = {
            if (behaviors.isEmpty()) {
                Text("当天无记录行为")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(behaviors.size) { idx ->
                        val b = behaviors[idx]
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text("开始：${dateFormatter.format(b.startTime)} ${timeFormatter.format(b.startTime)}")
                            Text("结束：${dateFormatter.format(b.endTime)} ${timeFormatter.format(b.endTime)}")
                            val durSec = b.totalDurationSeconds.toInt()
                            Text("时长：${durSec / 60}分${durSec % 60}秒；会话数：${b.sessionCount}")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
fun RangeDetailDialog(
    title: String,
    onDismiss: () -> Unit,
    behaviors: List<ContinuousBehavior>,
    timeFormatter: SimpleDateFormat,
    dateFormatter: SimpleDateFormat
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (behaviors.isEmpty()) {
                Text("无记录行为")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(behaviors.size) { idx ->
                        val b = behaviors[idx]
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text("开始：${dateFormatter.format(b.startTime)} ${timeFormatter.format(b.startTime)}")
                            Text("结束：${dateFormatter.format(b.endTime)} ${timeFormatter.format(b.endTime)}")
                            val durSec = b.totalDurationSeconds.toInt()
                            Text("时长：${durSec / 60}分${durSec % 60}秒；会话数：${b.sessionCount}")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}












