package com.example.testbutton

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import data.AppPreferences
import ui.LogScreen
import ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private lateinit var prefs: AppPreferences

    // 页面导航状态
    private var currentScreen by mutableStateOf("Main")

    // 用户选择的录音保存目录显示
    private var saveDirectory by mutableStateOf("未选择")
    private var saveTreeUri by mutableStateOf<Uri?>(null)

    // 无障碍服务状态
    private var isAccessibilityEnabled by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
            // 通知权限是可选的，但如果用户拒绝了麦克风权限则无法工作
            if (audioGranted) {
                startDetect()
            } else {
                // 处理权限拒绝
            }
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

    @OptIn(ExperimentalMaterial3Api::class)
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

        setContent {
            MaterialTheme {
                if (currentScreen == "Main") {
                    val currentDb by AudioStateRepo.currentDb.collectAsState()
                    val currentStatus by AudioStateRepo.currentStatus.collectAsState()
                    val lastFile by AudioStateRepo.lastSavedFile.collectAsState()
                    val isRunning by AudioStateRepo.isRunning.collectAsState()
                    val threshold by AudioStateRepo.currentThreshold.collectAsState()

                    Scaffold(
                        topBar = {
                            CenterAlignedTopAppBar(
                                title = { Text("voice黑匣子") },
                                actions = {
                                    TextButton(onClick = { currentScreen = "Settings" }) {
                                        Text("⚙ 设置")
                                    }
                                }
                            )
                        }
                    ) { innerPadding ->
                        MainUI(
                            modifier = Modifier.padding(innerPadding),
                            db = currentDb,
                            threshold = threshold,
                            statusText = currentStatus,
                            isRunning = isRunning,
                            file = lastFile,
                            start = { checkPermission() },
                            stop = { stopDetect() },
                            onViewLogs = { currentScreen = "Logs" }
                        )
                    }
                } else if (currentScreen == "Settings") {
                    BackHandler { currentScreen = "Main" }
                    SettingsScreen(
                        prefs = prefs,
                        onBack = { currentScreen = "Main" },
                        onChooseDirectory = { folderPicker.launch(null) },
                        saveDirectory = saveDirectory,
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        onOpenAccessibility = {
                            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    )
                } else if (currentScreen == "Logs") {
                    BackHandler { currentScreen = "Main" }
                    LogScreen(onBack = { currentScreen = "Main" })
                }
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
        val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val allGranted = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!allGranted) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
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
    modifier: Modifier = Modifier,
    db: Int,
    threshold: Int,
    statusText: String,
    isRunning: Boolean,
    file: String,
    start: () -> Unit,
    stop: () -> Unit,
    onViewLogs: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = onViewLogs,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
        ) {
            Text("📜 查看系统运行日志")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 分贝仪表卡片
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isRunning) "● 正在监听" else "○ 待命",
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
                    Text(text = "最后录音: $file", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        if (!isRunning) {
            Button(
                onClick = start,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("开启全天候监听", style = MaterialTheme.typography.titleMedium)
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
        
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "App 将在后台持续运行并根据声音自动录制",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
