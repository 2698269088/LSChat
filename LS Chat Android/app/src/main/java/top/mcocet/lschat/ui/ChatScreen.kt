package top.mcocet.lschat.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.mcocet.lschat.data.ApiClient
import top.mcocet.lschat.data.Message
import top.mcocet.lschat.data.SessionManager
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 聊天界面：支持私聊(isGroup=false)与群聊(isGroup=true)，文字 + 图片消息，轮询拉取新消息。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    api: ApiClient,
    session: SessionManager,
    isGroup: Boolean,
    targetId: Long,
    targetName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selfId by remember { mutableLongStateOf(-1L) }
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastSeen by remember { mutableLongStateOf(0L) }
    // 群聊时记录成员 id -> 昵称，用于显示他人消息的发送者
    var names by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    // 消息发送者头像缓存（值为 null 表示已请求过但未设置头像）
    var avatars by remember { mutableStateOf<Map<Long, Bitmap?>>(emptyMap()) }
    val listState = rememberLazyListState()

    suspend fun loadAvatar(id: Long) {
        if (id <= 0 || avatars.containsKey(id)) return
        avatars = avatars + (id to null) // 占位，避免轮询期间重复请求
        val bitmap = runCatching {
            val base64 = api.fetchAvatar(id)
            if (base64.isEmpty()) null
            else Base64.decode(base64, Base64.DEFAULT).let {
                BitmapFactory.decodeByteArray(it, 0, it.size)
            }
        }.getOrNull()
        avatars = avatars + (id to bitmap)
    }

    suspend fun refresh() {
        try {
            val after = messages.lastOrNull()?.id ?: 0L
            val incoming = if (isGroup) api.fetchGroupMessages(targetId, after)
            else api.fetchMessages(targetId, after)
            if (incoming.isNotEmpty()) {
                messages = messages + incoming
                // 同步全局已读位置，避免后台服务对当前打开的会话重复发通知
                val maxId = incoming.maxOf { it.id }
                if (maxId > lastSeen) {
                    lastSeen = maxId
                    session.saveLastSeenMessageId(lastSeen)
                }
            }
            error = null
        } catch (e: Exception) {
            error = e.message
        }
    }

    LaunchedEffect(Unit) {
        selfId = session.userId()
        lastSeen = session.lastSeenMessageId()
        if (isGroup) {
            runCatching {
                names = api.groupMembers(targetId).associate { it.id to it.username }
            }
        }
        // 首次加载历史消息
        try {
            messages = if (isGroup) api.fetchGroupMessages(targetId, 0L)
            else api.fetchMessages(targetId, 0L)
            error = null
        } catch (e: Exception) {
            error = e.message
        }
        // 轮询新消息
        while (true) {
            delay(1500)
            refresh()
        }
    }

    // WebSocket 模式：服务端实时推送的新消息直接插入当前会话
    LaunchedEffect(Unit) {
        if (api.useWebSocket) {
            api.messagePushes.collect { message ->
                val matches = if (isGroup) {
                    message.group == targetId
                } else {
                    message.group == 0L && (message.from == targetId || message.to == targetId)
                }
                if (matches && messages.none { it.id == message.id }) {
                    messages = messages + message
                    // 同步全局已读位置，避免后台服务对当前打开的会话重复发通知
                    if (message.id > lastSeen) {
                        lastSeen = message.id
                        session.saveLastSeenMessageId(lastSeen)
                    }
                }
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // 为消息中出现的发送者（含自己）按需加载头像
    LaunchedEffect(messages, selfId) {
        val senders = messages.map { it.from }.toSet() + selfId
        senders.forEach { loadAvatar(it) }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                sending = true
                error = null
                try {
                    val base64 = uriToCompressedBase64(context, uri)
                    if (isGroup) api.sendGroupMessage(targetId, Message.TYPE_IMAGE, base64)
                    else api.sendMessage(targetId, Message.TYPE_IMAGE, base64)
                    refresh()
                } catch (e: Exception) {
                    error = "图片发送失败：${e.message}"
                } finally {
                    sending = false
                }
            }
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                sending = true
                error = null
                try {
                    val base64 = uriToVideoBase64(context, uri)
                    if (isGroup) api.sendGroupMessage(targetId, Message.TYPE_VIDEO, base64)
                    else api.sendMessage(targetId, Message.TYPE_VIDEO, base64)
                    refresh()
                } catch (e: Exception) {
                    error = "视频发送失败：${e.message}"
                } finally {
                    sending = false
                }
            }
        }
    }

    var viewing by remember { mutableStateOf<Message?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (isGroup) "群聊 · $targetName" else targetName)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }) {
                    Icon(Icons.Filled.Add, contentDescription = "发送图片")
                }
                IconButton(onClick = {
                    videoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "发送视频")
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("输入消息...") },
                    maxLines = 4,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(4.dp))
                IconButton(
                    enabled = input.isNotBlank() && !sending,
                    onClick = {
                        val text = input.trim()
                        if (text.isEmpty()) return@IconButton
                        scope.launch {
                            sending = true
                            error = null
                            try {
                                if (isGroup) api.sendGroupMessage(targetId, Message.TYPE_TEXT, text)
                                else api.sendMessage(targetId, Message.TYPE_TEXT, text)
                                input = ""
                                refresh()
                            } catch (e: Exception) {
                                error = "发送失败：${e.message}"
                            } finally {
                                sending = false
                            }
                        }
                    }
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "发送")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    val isSelf = message.from == selfId
                    val senderName = if (isGroup && !isSelf) names[message.from] else null
                    MessageBubble(
                        message = message,
                        isSelf = isSelf,
                        senderName = senderName,
                        avatar = avatars[message.from],
                        onView = { viewing = message }
                    )
                }
            }
        }
    }

    // 点击图片/视频打开全屏查看窗口
    viewing?.let { message ->
        MediaViewerDialog(message = message, onClose = { viewing = null })
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    isSelf: Boolean,
    senderName: String?,
    avatar: Bitmap?,
    onView: (Message) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isSelf) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (!isSelf) {
                MessageAvatar(avatar = avatar, nameHint = senderName)
                Spacer(Modifier.width(8.dp))
            }
            Column(horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start) {
                if (senderName != null) {
                    Text(
                        text = senderName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                Surface(
                    color = if (isSelf) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (isSelf) 16.dp else 4.dp,
                        bottomEnd = if (isSelf) 4.dp else 16.dp
                    ),
                    modifier = Modifier.widthIn(max = 280.dp)
                ) {
                    when (message.type) {
                        Message.TYPE_IMAGE -> {
                            val bitmap = remember(message.content) {
                                runCatching {
                                    val bytes = Base64.decode(message.content, Base64.DEFAULT)
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                }.getOrNull()
                            }
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = "图片消息",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .heightIn(max = 240.dp)
                                        .clickable { onView(message) }
                                )
                            } else {
                                Text(
                                    "[图片无法显示]",
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        Message.TYPE_VIDEO -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable { onView(message) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("视频消息 · 点击播放", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        else -> Text(
                            text = message.content,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            if (isSelf) {
                Spacer(Modifier.width(8.dp))
                MessageAvatar(avatar = avatar, nameHint = "我")
            }
        }
    }
}

/** 消息旁的圆形头像，未设置头像时显示灰色圆形 + 首字符。 */
@Composable
private fun MessageAvatar(avatar: Bitmap?, nameHint: String?) {
    if (avatar != null) {
        Image(
            bitmap = avatar.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
        )
    } else {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = nameHint?.take(1) ?: "?",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 读取相册视频并转为 Base64（最大 40MB，不做转码）。 */
private fun uriToVideoBase64(context: android.content.Context, uri: Uri, maxBytes: Long = 40L * 1024 * 1024): String {
    val resolver = context.contentResolver
    val declared = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    if (declared > maxBytes) throw IllegalStateException("视频过大，最大支持 40MB")
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException("无法读取所选视频")
    if (bytes.size.toLong() > maxBytes) throw IllegalStateException("视频过大，最大支持 40MB")
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

/** 全屏查看窗口：图片直接预览，视频用 VideoView 播放，均可保存到应用相册目录。 */
@Composable
private fun MediaViewerDialog(message: Message, onClose: () -> Unit) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                when (message.type) {
                    Message.TYPE_IMAGE -> {
                        val bitmap = remember(message.content) {
                            runCatching {
                                val bytes = Base64.decode(message.content, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            }.getOrNull()
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                "图片无法显示",
                                color = Color.White,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                    Message.TYPE_VIDEO -> {
                        var videoUri by remember { mutableStateOf<Uri?>(null) }
                        LaunchedEffect(Unit) {
                            videoUri = runCatching {
                                val bytes = Base64.decode(message.content, Base64.DEFAULT)
                                val file = File(context.cacheDir, "lschat_video_${message.id}.mp4")
                                file.writeBytes(bytes)
                                Uri.fromFile(file)
                            }.getOrNull()
                        }
                        if (videoUri != null) {
                            AndroidView(
                                factory = { ctx ->
                                    VideoView(ctx).apply {
                                        setVideoURI(videoUri)
                                        val controller = MediaController(ctx)
                                        setMediaController(controller)
                                        controller.setAnchorView(this)
                                        start()
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                "视频加载中...",
                                color = Color.White,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                    else -> Text(
                        message.content,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                ) {
                    TextButton(onClick = { saveMedia(context, message) }) {
                        Text("保存", color = Color.White)
                    }
                    TextButton(onClick = onClose) {
                        Text("关闭", color = Color.White)
                    }
                }
            }
        }
    }
}

/** 将图片/视频保存到应用专属外部目录（无需存储权限）。 */
private fun saveMedia(context: android.content.Context, message: Message) {
    try {
        val bytes = Base64.decode(message.content, Base64.DEFAULT)
        val isVideo = message.type == Message.TYPE_VIDEO
        val dir = context.getExternalFilesDir(
            if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        ) ?: throw IllegalStateException("存储目录不可用")
        val name = (if (isVideo) "lschat_video_" else "lschat_image_") +
            message.id + "_" + System.currentTimeMillis() + if (isVideo) ".mp4" else ".jpg"
        val file = File(dir, name)
        file.writeBytes(bytes)
        Toast.makeText(context, "已保存到 " + file.absolutePath, Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "保存失败：" + e.message, Toast.LENGTH_LONG).show()
    }
}

/** 读取相册图片，缩放压缩后转为 Base64（JPEG）。 */
private fun uriToCompressedBase64(context: android.content.Context, uri: Uri, maxDim: Int = 1280): String {
    val resolver = context.contentResolver

    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) > maxDim) {
        sample *= 2
    }
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: throw IllegalStateException("无法读取所选图片")

    val output = ByteArrayOutputStream()
    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, output)
    return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
}
