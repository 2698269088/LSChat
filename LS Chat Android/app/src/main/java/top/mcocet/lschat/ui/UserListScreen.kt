package top.mcocet.lschat.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.mcocet.lschat.data.ApiClient
import top.mcocet.lschat.data.ApiException
import top.mcocet.lschat.data.GroupInfo
import top.mcocet.lschat.data.SessionManager
import top.mcocet.lschat.data.User
import java.io.ByteArrayOutputStream

/** 主界面：联系人 / 群组 双标签，群聊创建、搜索加入、申请审核与群管理。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserListScreen(
    api: ApiClient,
    session: SessionManager,
    onOpenChat: (isGroup: Boolean, targetId: Long, name: String) -> Unit,
    onLogout: () -> Unit
) {
    var users by remember { mutableStateOf<List<User>>(emptyList()) }
    var groups by remember { mutableStateOf<List<GroupInfo>>(emptyList()) }
    var selfName by remember { mutableStateOf("") }
    var selfId by remember { mutableStateOf(-1L) }
    var avatars by remember { mutableStateOf<Map<Long, Bitmap>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableStateOf(0) }
    var showCreateGroup by remember { mutableStateOf(false) }
    var showSearchGroup by remember { mutableStateOf(false) }
    var showRequests by remember { mutableStateOf(false) }
    var showAddFriend by remember { mutableStateOf(false) }
    var showFriendRequests by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var manageTarget by remember { mutableStateOf<GroupInfo?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    suspend fun loadAvatar(id: Long) {
        if (id < 0 || avatars.containsKey(id)) return
        try {
            val base64 = api.fetchAvatar(id)
            if (base64.isNotEmpty()) {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bmp ->
                    avatars = avatars + (id to bmp)
                }
            }
        } catch (_: Exception) {
            // 无头像或网络异常时使用默认图标
        }
    }

    // 从相册选择头像，选中后压缩上传
    val pickAvatar = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null || selfId < 0) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                api.setAvatar(compressAvatar(context, uri))
                avatars = avatars - selfId
                loadAvatar(selfId)
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    suspend fun refreshGroups() {
        try {
            groups = api.myGroups()
        } catch (e: Exception) {
            error = e.message
        }
    }

    suspend fun refresh() {
        try {
            users = api.users()
            groups = api.myGroups()
            error = null
            users.forEach { if (it.id !in avatars) loadAvatar(it.id) }
        } catch (e: ApiException) {
            error = e.message
            if (e.message?.contains("未登录") == true || e.message?.contains("过期") == true) {
                session.clearSession()
                onLogout()
            }
        } catch (e: Exception) {
            error = "连接失败：${e.message}"
        }
    }

    LaunchedEffect(Unit) {
        // Android 13+ 需要运行时请求通知权限，否则收不到消息通知
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        selfName = session.userName() ?: ""
        selfId = session.userId()
        loadAvatar(selfId)
        refresh()
        while (true) {
            delay(5000)
            refresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LS Chat${if (selfName.isEmpty()) "" else " · $selfName"}") },
                actions = {
                    IconButton(onClick = { scope.launch { refresh() } }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = { showProfile = true }) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = "我的资料")
                    }
                    IconButton(onClick = {
                        scope.launch {
                            session.clearSession()
                            onLogout()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "注销")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("联系人") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("群组") }
                )
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            if (selectedTab == 0) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = { showAddFriend = true }) { Text("加好友") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { showFriendRequests = true }) { Text("好友申请") }
                    }
                    if (users.isEmpty()) {
                        Text(
                            "还没有好友。把你的 ID（$selfId）告诉对方，或通过“加好友”输入对方 ID 发送申请。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(users, key = { it.id }) { user ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenChat(false, user.id, user.username) }
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val avatar = avatars[user.id]
                                if (avatar != null) {
                                    Image(
                                        bitmap = avatar.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(40.dp).clip(CircleShape)
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.Person,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(user.username, style = MaterialTheme.typography.titleMedium)
                                    if (user.subtitle.isNotEmpty()) {
                                        Text(
                                            user.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            } else {
                GroupTabContent(
                    groups = groups,
                    onCreateGroup = { showCreateGroup = true },
                    onSearchGroup = { showSearchGroup = true },
                    onRequests = { showRequests = true },
                    onManage = { group -> manageTarget = group },
                    onOpenGroup = { group -> onOpenChat(true, group.id, group.name) }
                )
            }
        }
    }

    if (showProfile) {
        MyProfileDialog(
            api = api,
            selfName = selfName,
            selfId = selfId,
            avatar = avatars[selfId],
            onChangeAvatar = { pickAvatar.launch("image/*") },
            onSaved = { scope.launch { refresh() } },
            onDismiss = { showProfile = false }
        )
    }
    if (showAddFriend) {
        AddFriendDialog(
            api = api,
            onDismiss = { showAddFriend = false },
            onChanged = { scope.launch { refresh() } }
        )
    }
    if (showFriendRequests) {
        FriendRequestsDialog(
            api = api,
            onDismiss = { showFriendRequests = false },
            onChanged = { scope.launch { refresh() } }
        )
    }
    if (showCreateGroup) {
        CreateGroupDialog(
            api = api,
            onDismiss = { showCreateGroup = false },
            onCreated = {
                scope.launch { refreshGroups() }
            }
        )
    }
    if (showSearchGroup) {
        SearchGroupDialog(
            api = api,
            onDismiss = { showSearchGroup = false },
            onChanged = { scope.launch { refreshGroups() } }
        )
    }
    if (showRequests) {
        JoinRequestsDialog(
            api = api,
            onDismiss = { showRequests = false },
            onChanged = { scope.launch { refreshGroups() } }
        )
    }
    manageTarget?.let { group ->
        ManageGroupDialog(
            api = api,
            group = group,
            onDismiss = { manageTarget = null },
            onChanged = { scope.launch { refreshGroups() } }
        )
    }
}

@Composable
private fun GroupTabContent(
    groups: List<GroupInfo>,
    onCreateGroup: () -> Unit,
    onSearchGroup: () -> Unit,
    onRequests: () -> Unit,
    onManage: (GroupInfo) -> Unit,
    onOpenGroup: (GroupInfo) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onCreateGroup) { Text("创建群") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onSearchGroup) { Text("搜群加入") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onRequests) { Text("入群申请") }
        }
        if (groups.isEmpty()) {
            Text(
                "还没有加入任何群。可通过“创建群”建立新群，或通过“搜群加入”输入群 ID 加入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(groups, key = { it.id }) { group ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenGroup(group) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(group.name, style = MaterialTheme.typography.titleMedium)
                        val info = if (group.mutedAll) "全员禁言中 · " else ""
                        val extra = if (group.role == "owner") "（群主）"
                        else if (group.role == "admin") "（管理员）"
                        else ""
                        Text(
                            "${info}${group.memberCount}人$extra · 群ID ${group.id}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (group.canManage) {
                        TextButton(onClick = { onManage(group) }) { Text("管理") }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/** 我的资料对话框：显示头像、用户名、ID，可更换头像、编辑签名与状态。 */
@Composable
private fun MyProfileDialog(
    api: ApiClient,
    selfName: String,
    selfId: Long,
    avatar: Bitmap?,
    onChangeAvatar: () -> Unit,
    onSaved: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var signature by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var tip by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val (sig, st) = api.getProfile()
            signature = sig
            status = st
        } catch (e: Exception) {
            error = e.message
        } finally {
            loaded = true
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("我的资料") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (avatar != null) {
                    Image(
                        bitmap = avatar.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp).clip(CircleShape)
                    )
                } else {
                    Icon(
                        Icons.Filled.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(selfName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "用户 ID：$selfId",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = signature,
                    onValueChange = { if (it.length <= 30) signature = it },
                    label = { Text("个性签名（${signature.length}/30）") },
                    singleLine = true,
                    enabled = loaded && !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = status,
                    onValueChange = { if (it.length <= 20) status = it },
                    label = { Text("个人状态（${status.length}/20）") },
                    singleLine = true,
                    enabled = loaded && !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                tip?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = loaded && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        tip = null
                        try {
                            api.setProfile(signature.trim(), status.trim())
                            tip = "已保存"
                            onSaved()
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onChangeAvatar) { Text("更换头像") }
        }
    )
}

/** 压缩头像：缩放到最长边 256 的 JPEG Base64。 */
private fun compressAvatar(context: android.content.Context, uri: Uri): String {
    val original = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it)
    } ?: throw IllegalArgumentException("无法读取所选图片")
    val scale = minOf(1f, 256f / maxOf(original.width, original.height))
    val width = maxOf(1, (original.width * scale).toInt())
    val height = maxOf(1, (original.height * scale).toInt())
    val scaled = Bitmap.createScaledBitmap(original, width, height, true)
    val output = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)
    return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
}
