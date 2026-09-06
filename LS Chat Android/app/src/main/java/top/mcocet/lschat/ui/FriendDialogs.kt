package top.mcocet.lschat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.mcocet.lschat.data.ApiClient
import top.mcocet.lschat.data.FriendRequest
import top.mcocet.lschat.data.User

/** 按用户 ID 查找并发送好友申请。 */
@Composable
fun AddFriendDialog(
    api: ApiClient,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var userIdText by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<User?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun search() {
        val id = userIdText.trim().toLongOrNull() ?: run {
            error = "请输入有效的用户 ID"
            return
        }
        scope.launch {
            busy = true
            error = null
            message = null
            try {
                result = api.lookupUser(id)
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("添加好友") },
        text = {
            Column {
                OutlinedTextField(
                    value = userIdText,
                    onValueChange = { userIdText = it },
                    label = { Text("用户 ID") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                result?.let { user ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("用户名：${user.username}", style = MaterialTheme.typography.bodyMedium)
                    Text("用户 ID：${user.id}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            val searched = result != null
            if (!searched) {
                TextButton(enabled = !busy, onClick = { search() }) {
                    if (busy) CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)
                    else Text("查找")
                }
            } else {
                TextButton(enabled = !busy, onClick = {
                    val user = result ?: return@TextButton
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            message = api.sendFriendRequest(user.id)
                            result = null
                            onChanged()
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                }) {
                    Text("发送申请")
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 好友申请审批对话框。 */
@Composable
fun FriendRequestsDialog(
    api: ApiClient,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var requests by remember { mutableStateOf<List<FriendRequest>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        busy = true
        error = null
        try {
            requests = api.friendRequests()
        } catch (e: Exception) {
            error = e.message
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) { load() }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("好友申请") },
        text = {
            Column {
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (requests.isEmpty() && !busy) {
                    Text("（暂无待处理的好友申请）", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(requests, key = { it.id }) { request ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(request.username, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "用户 ID ${request.userId}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            api.handleFriendRequest(request.id, true)
                                            requests = requests.filter { it.id != request.id }
                                            onChanged()
                                        } catch (e: Exception) {
                                            error = e.message
                                        }
                                    }
                                }) { Text("同意") }
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            api.handleFriendRequest(request.id, false)
                                            requests = requests.filter { it.id != request.id }
                                            onChanged()
                                        } catch (e: Exception) {
                                            error = e.message
                                        }
                                    }
                                }) { Text("拒绝") }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { load() }
            }) { Text("刷新") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
