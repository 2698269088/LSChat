package top.mcocet.lschat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.mcocet.lschat.data.ApiClient
import top.mcocet.lschat.data.SessionManager

/** 首次启动：配置服务器地址与通信协议（默认 WebSocket）。 */
@Composable
fun ServerConfigScreen(
    api: ApiClient,
    session: SessionManager,
    onSaved: () -> Unit
) {
    var host by remember { mutableStateOf(api.host) }
    var port by remember { mutableStateOf(api.port.toString()) }
    var protocol by remember { mutableStateOf(api.protocol) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeContentPadding()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("LS Chat", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "首次使用，请配置服务器地址",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = host,
            onValueChange = { host = it.trim() },
            label = { Text("服务器地址") },
            placeholder = { Text("例如 192.168.1.100 或 example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text("端口") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "通信协议",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = protocol == "websocket",
                onClick = { protocol = "websocket" },
                label = { Text("WebSocket（默认）") }
            )
            FilterChip(
                selected = protocol == "http",
                onClick = { protocol = "http" },
                label = { Text("HTTP 轮询") }
            )
        }
        Spacer(Modifier.height(24.dp))

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
        }
        notice?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
        }

        Button(
            onClick = {
                if (loading) return@Button
                val h = host.trim()
                val p = port.toIntOrNull()
                if (h.isEmpty() || p == null || p <= 0 || p > 65535) {
                    error = "请输入有效的服务器地址和端口"
                    return@Button
                }
                scope.launch {
                    loading = true
                    error = null
                    notice = null
                    api.host = h
                    api.port = p
                    api.protocol = protocol
                    try {
                        val info = api.ping()
                        session.saveServer(h, p, protocol)
                        notice = "连接成功：$info"
                        onSaved()
                    } catch (e: Exception) {
                        error = "无法连接服务器：${e.message}"
                    } finally {
                        loading = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (loading) "正在连接..." else "测试连接并保存")
        }

        if (api.pinnedCert != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        session.clearPinnedCert()
                        api.pinnedCert = null
                        notice = "已清除信任的服务器证书，下次连接将重新信任"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("清除服务器证书锁定")
            }
        }
    }
}
