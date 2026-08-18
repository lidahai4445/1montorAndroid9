package ui

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.testbutton.AudioStateRepo
import data.AppPreferences
import data.RecordingMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: AppPreferences,
    onBack: () -> Unit,
    onChooseDirectory: () -> Unit,
    saveDirectory: String,
    isAccessibilityEnabled: Boolean,
    onOpenAccessibility: () -> Unit
) {
    // 状态同步
    var currentMode by remember { mutableStateOf(prefs.getRecordingMode()) }
    var minSaveDuration by remember { mutableStateOf(prefs.getMinSaveDuration() / 1000) }
    var stopEvalDelay by remember { mutableStateOf(prefs.getStopEvalDelay() / 1000) }
    var triggerOffset by remember { mutableStateOf(prefs.getTriggerOffset()) }
    var trackingSpeed by remember { mutableStateOf(prefs.getTrackingSpeed()) }
    var fallSpeed by remember { mutableStateOf(prefs.getFallSpeed()) }
    
    var alertModeEnabled by remember { mutableStateOf(prefs.isAlertModeEnabled()) }
    var alertThresholdHours by remember { mutableStateOf(prefs.getAlertTimeThreshold() / (1000 * 60 * 60)) }

    // 当模式切换时，自动刷新下方的数值显示（同步预设）
    LaunchedEffect(currentMode) {
        minSaveDuration = prefs.getMinSaveDuration() / 1000
        stopEvalDelay = prefs.getStopEvalDelay() / 1000
        triggerOffset = prefs.getTriggerOffset()
        trackingSpeed = prefs.getTrackingSpeed()
        fallSpeed = prefs.getFallSpeed()
        
        // 更新全局 Repo
        AudioStateRepo.recordingMode.value = currentMode
        AudioStateRepo.triggerOffset.value = triggerOffset
        AudioStateRepo.trackingSpeed.value = trackingSpeed
        AudioStateRepo.fallSpeed.value = fallSpeed
        AudioStateRepo.minSaveDurationMs.value = minSaveDuration * 1000
        AudioStateRepo.stopEvalDelayMs.value = stopEvalDelay * 1000
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- 场景模式选择 ---
            SectionTitle("场景模式 (黑匣子预设)")
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    val modes = listOf(
                        RecordingMode.INDOOR to "室内",
                        RecordingMode.OUTDOOR to "户外",
                        RecordingMode.AUTO to "自动",
                        RecordingMode.MANUAL to "手动"
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        modes.forEach { (mode, label) ->
                            FilterChip(
                                selected = (currentMode == mode),
                                onClick = { 
                                    currentMode = mode
                                    prefs.saveRecordingMode(mode)
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                    Text(
                        text = when(currentMode) {
                            RecordingMode.INDOOR -> "💡 室内模式：提高灵敏度，适合安静环境。"
                            RecordingMode.OUTDOOR -> "🌲 户外模式：高强度过滤，适合风大或吵杂环境。"
                            RecordingMode.AUTO -> "⚖️ 自动模式：平衡灵敏度与稳定性。"
                            RecordingMode.MANUAL -> "🛠️ 手动模式：允许自由调节下方所有参数。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // --- 触发灵敏度 ---
            val isManual = currentMode == RecordingMode.MANUAL
            
            SectionTitle("灵敏度设置")
            Text("触发灵敏度偏移 (环境底噪 + ${triggerOffset}dB):", 
                 color = if (isManual) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            Slider(
                value = triggerOffset.toFloat(),
                onValueChange = {
                    triggerOffset = it.toInt()
                    prefs.saveTriggerOffset(it.toInt())
                    AudioStateRepo.triggerOffset.value = it.toInt()
                },
                valueRange = 2f..20f,
                steps = 17,
                enabled = isManual
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text("环境追踪变率 (数值越高，适应噪音越快): $trackingSpeed",
                 color = if (isManual) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            Slider(
                value = trackingSpeed.toFloat(),
                onValueChange = {
                    trackingSpeed = it.toInt()
                    prefs.saveTrackingSpeed(it.toInt())
                    AudioStateRepo.trackingSpeed.value = it.toInt()
                },
                valueRange = 1f..10f,
                steps = 9,
                enabled = isManual
            )
            Text(
                text = "若环境有持续大风或空调声，请调高此值",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text("环境下降变率 (数值越高，适应静音越快): $fallSpeed",
                 color = if (isManual) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            Slider(
                value = fallSpeed.toFloat(),
                onValueChange = {
                    fallSpeed = it.toInt()
                    prefs.saveFallSpeed(it.toInt())
                    AudioStateRepo.fallSpeed.value = it.toInt()
                },
                valueRange = 1f..10f,
                steps = 9,
                enabled = isManual
            )
            Text(
                text = "环境变安静时，基线下降的速度",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            // --- 高级录音控制 ---
            SectionTitle("高级录音控制")
            
            Text("触发停止判定延迟: ${stopEvalDelay}秒", 
                 color = if (isManual) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            Slider(
                value = stopEvalDelay.toFloat(),
                onValueChange = { 
                    stopEvalDelay = it.toLong()
                    val ms = it.toLong() * 1000
                    prefs.saveStopEvalDelay(ms)
                    AudioStateRepo.stopEvalDelayMs.value = ms
                },
                valueRange = 10f..60f,
                steps = 10,
                enabled = isManual
            )

            Text("最短保存时长: ${minSaveDuration}秒", 
                 color = if (isManual) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            Slider(
                value = minSaveDuration.toFloat(),
                onValueChange = { 
                    minSaveDuration = it.toLong()
                    val ms = it.toLong() * 1000
                    prefs.saveMinSaveDuration(ms)
                    AudioStateRepo.minSaveDurationMs.value = ms
                },
                valueRange = 5f..120f,
                steps = 23,
                enabled = isManual
            )

            // --- 存储设置 ---
            SectionTitle("存储设置")
            OutlinedButton(onClick = onChooseDirectory, modifier = Modifier.fillMaxWidth()) {
                Text("文件存放路径: $saveDirectory")
            }

            // --- 后台保活 ---
            SectionTitle("后台保活与稳定性")
            Button(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) {
                Text(if (isAccessibilityEnabled) "✅ 无障碍服务：已开启" else "❌ 无障碍服务：点击开启")
            }

            // --- 自动清理 ---
            SectionTitle("自动清理策略")
            val cleanupOptions = listOf(0 to "不清理", 3 to "3天前", 7 to "7天前", 30 to "一个月前")
            var selectedCleanup by remember { mutableStateOf(prefs.getCleanupDays()) }
            
            cleanupOptions.forEach { (days, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = (selectedCleanup == days),
                        onClick = { 
                            selectedCleanup = days
                            prefs.saveCleanupDays(days)
                        }
                    )
                    Text(label)
                }
            }

            // --- 安全警报模式 ---
            SectionTitle("安全警报 (SOS 模式)")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("开启警报模式")
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = alertModeEnabled,
                    onCheckedChange = {
                        alertModeEnabled = it
                        prefs.saveAlertModeEnabled(it)
                        AudioStateRepo.isAlertModeEnabled.value = it
                    }
                )
            }
            
            if (alertModeEnabled) {
                Text("无声报警阈值: ${alertThresholdHours}小时")
                Slider(
                    value = alertThresholdHours.toFloat(),
                    onValueChange = {
                        alertThresholdHours = it.toLong()
                        val ms = it.toLong() * 60 * 60 * 1000
                        prefs.saveAlertTimeThreshold(ms)
                        AudioStateRepo.alertTimeThresholdMs.value = ms
                    },
                    valueRange = 1f..48f,
                    steps = 47
                )
            }
            
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}
