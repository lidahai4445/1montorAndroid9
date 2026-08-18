package com.example.testbutton

import data.RecordingMode
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
    val currentThreshold = MutableStateFlow(38)

    // 当前场景模式
    val recordingMode = MutableStateFlow(RecordingMode.AUTO)

    // 触发灵敏度偏移 (环境声 + Offset)
    val triggerOffset = MutableStateFlow(8)

    // 环境追踪变率 (1-10)
    val trackingSpeed = MutableStateFlow(5)

    // 环境下降变率 (1-10)
    val fallSpeed = MutableStateFlow(5)

    // --- 新增设置项的实时流 ---
    
    // 录音后多久才开始判断停止
    val stopEvalDelayMs = MutableStateFlow(15000L)
    
    // 少于多少毫秒的文件不保存
    val minSaveDurationMs = MutableStateFlow(20000L)
    
    // 是否开启警报
    val isAlertModeEnabled = MutableStateFlow(false)
    
    // 多长时间没有有效录音就报警
    val alertTimeThresholdMs = MutableStateFlow(24 * 60 * 60 * 1000L)

    // 最后一次有效录音时间
    val lastValidRecordTime = MutableStateFlow(System.currentTimeMillis())
}
