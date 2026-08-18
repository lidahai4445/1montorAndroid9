package com.example.testbutton

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var prefs: AppPreferences

    // 用户选择的录音保存目录显示
    private var saveDirectory by mutableStateOf("未选择")
    private var saveTreeUri by mutableStateOf<Uri?>(null)

    // 清理策略
    private var cleanupDays by mutableIntStateOf(0)

    // 无障碍服务状态
    private var isAccessibilityEnabled by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startDetect()
        }

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                
                prefs.saveStorageUri(uri.toString())
                saveTreeUri = uri
                updateDirectoryDisplay(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = AppPreferences(this)
        
        // 读取保存的目录
        prefs.getStorageUri()?.let { uriString ->
            val uri = Uri.parse(uriString)
            saveTreeUri = uri
            updateDirectoryDisplay(uri)
        }
        cleanupDays = prefs.getCleanupDays()

        setContent {
            MaterialTheme {
                val currentDb by AudioStateRepo.currentDb.collectAsState()
                val currentStatus by AudioStateRepo.currentStatus.collectAsState()
                val lastFile by AudioStateRepo.lastSavedFile.collectAsState()
                val isRunning by AudioStateRepo.isRunning.collectAsState()
                val threshold by AudioStateRepo.currentThreshold.collectAsState()

                MainUI(
                    db = currentDb,
                    threshold = threshold,
                    statusText = currentStatus,
                    isRunning = isRunning,
                    file = lastFile,
                    saveDirectory = saveDirectory,
                    cleanupDays = cleanupDays,
                    isAccessibilityEnabled = isAccessibilityEnabled,
                    onCleanupDaysChanged = { 
                        cleanupDays = it
                        prefs.saveCleanupDays(it)
                    },
                    start = { checkPermission() },
                    stop = { stopDetect() },
                    chooseDirectory = { folderPicker.launch(null) },
                    openAccessibilitySettings = {
                        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                )
            }
        }
    }

    private fun updateDirectoryDisplay(uri: Uri) {
        val path = uri.path ?: ""
        val folderName = if (path.contains(":")) path.substringAfterLast(":") else uri.toString()
        saveDirectory = folderName
    }

    override fun onResume() {
        super.onResume()
        isAccessibilityEnabled = isAccessibilityServiceEnabled(this, KeepAliveService::class.java)
    }

    private fun isAccessibilityServiceEnabled(context: android.content.Context, service: Class<out android.accessibilityservice.AccessibilityService>): Boolean {
        val expectedComponentName = android.content.ComponentName(context, service)
        val enabledServicesSetting = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = android.content.ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) return true
        }
        return false
    }

    private fun checkPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startDetect()
        }
    }

    private fun startDetect() {
        startService(Intent(this, AudioMonitorService::class.java))
    }

    private fun stopDetect() {
        stopService(Intent(this, AudioMonitorService::class.java))
    }
}

@Composable
fun MainUI(
    db: Int,
    threshold: Int,
    statusText: String,
    isRunning: Boolean,
    file: String,
    saveDirectory: String,
    cleanupDays: Int,
    isAccessibilityEnabled: Boolean,
    onCleanupDaysChanged: (Int) -> Unit,
    start: () -> Unit,
    stop: () -> Unit,
    chooseDirectory: () -> Unit,
    openAccessibilitySettings: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "智能音频管家",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(32.dp))

            // 分贝仪表卡片
            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isRunning) "● 录音中" else "○ 待命",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = "$db dB",
                        style = MaterialTheme.typography.displayLarge
                    )
                    Text(
                        text = "触发阈值：$threshold dB",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 状态卡片
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "系统状态", style = MaterialTheme.typography.titleMedium)
                    Text(text = statusText, style = MaterialTheme.typography.bodyLarge)
                    if (file.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "已保存: $file", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 设置区域
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "⚙ 配置选项", style = MaterialTheme.typography.titleMedium)
                }

                Button(onClick = chooseDirectory, modifier = Modifier.fillMaxWidth()) {
                    Text("选择录音存放文件夹")
                }
                Text(
                    text = "当前目录: $saveDirectory",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = openAccessibilitySettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isAccessibilityEnabled) "保活服务：正常" else "保活服务：点击开启")
                }

                Button(
                    onClick = { onCleanupDaysChanged(if (cleanupDays == 0) 30 else 0) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (cleanupDays == 0) "自动清理：已关闭" else "自动清理：30天以前")
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            if (!isRunning) {
                Button(
                    onClick = start,
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("开启监听", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Button(
                    onClick = stop,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("停止监听", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
