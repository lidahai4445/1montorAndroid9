package com.example.testbutton

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：用于提升进程优先级，防止被系统清理
 */
class KeepAliveService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要处理具体事件
    }

    override fun onInterrupt() {
        // 中断处理
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 服务启动成功
    }
}
