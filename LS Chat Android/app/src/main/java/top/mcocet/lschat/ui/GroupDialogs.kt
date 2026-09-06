package top.mcocet.lschat.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import top.mcocet.lschat.data.GroupInfo
import top.mcocet.lschat.data.GroupMember
import top.mcocet.lschat.data.JoinRequest

/** 创建群对话框：输入名称并选择入群方式。 */
@Composable
fun CreateGroupDialog(
    api: ApiClient,
    onDismiss: () -> Unit,
    onCreated: (GroupInfo) -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var policy by remember { mutableStateOf("approval") } // approval / open
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("创建群聊") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("群名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("入群方式：", style = MaterialTheme.typography.bodyMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = policy == "approval") { policy = "approval" }
                        .padding(vertical = 4.dp)
                ) {
                    Checkbox(checked = policy == "approval", onCheckedChange = { policy = "approval" })
                    Text("需要管理员审核")
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = policy == "open") { policy = "open" }
                        .padding(vertical = 4.dp)
                ) {
                    Checkbox(checked = policy == "open", onCheckedChange = { policy = "open" })
                    Text("允许直接加入（无需确认）")
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val groupName = name.trim()
                if (groupName.isEmpty() || groupName.length > 32) {
                    error = "群名称需为 1-32 个字符"
                    return@TextButton
                }
                scope.launch {
                    busy = true
                    error = null
                    try {
                        onCreated(api.createGroup(groupName, policy))
                        onDismiss()
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        busy = false
                    }
                }
            }) {
                if (busy) CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)
                else Text("创建")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } }
    )
}

/** 按群 ID 搜索并加入。 */
@Composable
fun SearchGroupDialog(
    api: ApiClient,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var groupIdText by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<GroupInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun search() {
        val id = groupIdText.trim().toLongOrNull() ?: run {
            error = "请输入有效的群 ID"
            return
        }
        scope.launch {
            busy = true
            error = null
            message = null
            try {
                result = api.searchGroup(id)
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("搜索群组") },
        text = {
            Column {
                OutlinedTextField(
                    value = groupIdText,
                    onValueChange = { groupIdText = it },
                    label = { Text("群 ID") },
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
                result?.let { group ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("群名称：${group.name}", style = MaterialTheme.typography.bodyMedium)
                    Text("群 ID：${group.id}", style = MaterialTheme.typography.bodyMedium)
                    Text("成员数：${group.memberCount}", style = MaterialTheme.typography.bodyMedium)
                    val policy = if (group.joinPolicy == "open") "允许直接加入" else "需要管理员审核"
                    Text("入群方式：$policy", style = MaterialTheme.typography.bodyMedium)
                    if (group.pending) {
                        Text(
                            "已提交申请，等待群主/管理员审核",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (group.role != null) {
                        Text(
                            "你已是该群成员（${roleLabel(group.role)}）",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            val group = result
            when {
                busy -> TextButton(enabled = false, onClick = {}) {
                    CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)
                }
                group == null -> TextButton(onClick = { search() }) { Text("搜索") }
                group.role != null -> TextButton(onClick = onDismiss) { Text("关闭") }
                group.pending -> TextButton(enabled = false, onClick = {}) { Text("已申请，等待审核") }
                else -> TextButton(onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            message = api.joinGroup(group.id)
                            result = null
                            onChanged()
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                }) {
                    Text(if (group.joinPolicy == "open") "加入该群" else "申请入群")
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 入群申请审批对话框。 */
@Composable
fun JoinRequestsDialog(
    api: ApiClient,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var requests by remember { mutableStateOf<List<JoinRequest>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        busy = true
        error = null
        try {
            requests = api.groupRequests()
        } catch (e: Exception) {
            error = e.message
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) { load() }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("入群申请") },
        text = {
            Column {
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (requests.isEmpty() && !busy) {
                    Text("（暂无待处理的入群申请）", style = MaterialTheme.typography.bodyMedium)
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
                                        "申请加入 ${request.groupName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            api.handleGroupRequest(request.id, true)
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
                                            api.handleGroupRequest(request.id, false)
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

/** 群管理对话框（群主/管理员）：改名、入群方式、全员禁言、设置管理员、禁言成员。 */
@Composable
fun ManageGroupDialog(
    api: ApiClient,
    group: GroupInfo,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val isOwner = group.role == "owner"
    var name by remember { mutableStateOf(group.name) }
    var policy by remember { mutableStateOf(group.joinPolicy) }
    var mutedAll by remember { mutableStateOf(group.mutedAll) }
    var members by remember { mutableStateOf<List<GroupMember>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var savedTip by remember { mutableStateOf<String?>(null) }

    suspend fun loadMembers() {
        busy = true
        error = null
        try {
            members = api.groupMembers(group.id)
        } catch (e: Exception) {
            error = e.message
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) { loadMembers() }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("群管理 · ${group.name}") },
        text = {
            Column {
                Text(
                    "群 ID：${group.id}　我的角色：${roleLabel(group.role)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("群名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("入群方式：", style = MaterialTheme.typography.bodyMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = policy == "approval") { policy = "approval" }
                ) {
                    Checkbox(checked = policy == "approval", onCheckedChange = { policy = "approval" })
                    Text("需要管理员审核")
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = policy == "open") { policy = "open" }
                ) {
                    Checkbox(checked = policy == "open", onCheckedChange = { policy = "open" })
                    Text("允许直接加入")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = mutedAll, onCheckedChange = { mutedAll = it })
                    Text("全员禁言")
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            savedTip = null
                            try {
                                api.renameGroup(group.id, name.trim())
                                api.updateGroupSettings(group.id, policy, mutedAll)
                                savedTip = "设置已保存"
                                onChanged()
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("保存设置") }
                }
                savedTip?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Text("成员（${members.size}人）：", style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(members, key = { it.id }) { member ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(member.username, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    roleLabel(member.role) + if (member.muted) " · 已禁言" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isOwner && member.role != "owner") {
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            api.setGroupRole(
                                                group.id, member.id,
                                                if (member.role == "admin") "member" else "admin"
                                            )
                                            loadMembers()
                                        } catch (e: Exception) {
                                            error = e.message
                                        }
                                    }
                                }) {
                                    Text(if (member.role == "admin") "取消管理员" else "设为管理员")
                                }
                            }
                            if (member.role != "owner" && (member.role != "admin" || isOwner)) {
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            api.setGroupMute(group.id, member.id, !member.muted)
                                            loadMembers()
                                        } catch (e: Exception) {
                                            error = e.message
                                        }
                                    }
                                }) {
                                    Text(if (member.muted) "解除禁言" else "禁言")
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

private fun roleLabel(role: String?): String = when (role) {
    "owner" -> "群主"
    "admin" -> "管理员"
    else -> "成员"
}
