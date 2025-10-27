package com.dazo66.milkmilk

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 全局聚合更新通知：当聚合表写入完成时发出事件，供首页监听刷新。
 */
object AggregationEvents {
    private val _updates = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val updates = _updates.asSharedFlow()

    fun notifyUpdated() {
        _updates.tryEmit(Unit)
    }
}
