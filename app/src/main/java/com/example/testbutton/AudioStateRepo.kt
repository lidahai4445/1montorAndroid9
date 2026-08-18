package com.example.testbutton

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 全局状态仓库，用于 Service 与 Activity 通信
 */
object AudioStateRepo {
    // 实时分贝值
    val currentDb = MutableStateFlow(0)

    // 当前状态文字
    val currentStatus = MutableStateFlow("已停止")

    // 最新保存的文件路径
    val lastSavedFile = MutableStateFlow("")

    // 是否正在运行 (用于 UI 按钮状态)
    val isRunning = MutableStateFlow(false)

    // 是否正在录音
    val isRecording = MutableStateFlow(false)

    // 当前动态阈值
    val currentThreshold = MutableStateFlow(-45)
}
