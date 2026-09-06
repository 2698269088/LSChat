package top.mcocet.lschat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import top.mcocet.lschat.data.ApiClient
import top.mcocet.lschat.data.SessionManager
import top.mcocet.lschat.service.MessagePollingService
import top.mcocet.lschat.ui.ChatScreen
import top.mcocet.lschat.ui.LoginScreen
import top.mcocet.lschat.ui.RegisterScreen
import top.mcocet.lschat.ui.ServerConfigScreen
import top.mcocet.lschat.ui.UserListScreen
import top.mcocet.lschat.ui.theme.LSChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LSChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val session = remember { SessionManager(context) }
    val scope = rememberCoroutineScope()
    val api = remember { ApiClient(session, scope) }

    var ready by remember { mutableStateOf(false) }
    var startRoute by remember { mutableStateOf("config") }

    LaunchedEffect(Unit) {
        val host = session.serverHost()
        val port = session.serverPort()
        api.host = host ?: ""
        api.port = port
        api.protocol = session.protocol()
        api.token = session.token() ?: ""
        api.pinnedCert = session.pinnedCert()
        startRoute = when {
            host.isNullOrEmpty() -> "config"
            api.token.isEmpty() -> "login"
            else -> "users"
        }
        ready = true
        // 已登录则启动后台消息轮询服务
        if (api.token.isNotEmpty()) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MessagePollingService::class.java)
            )
        }
    }

    if (!ready) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = startRoute) {
        composable("config") {
            ServerConfigScreen(
                api = api,
                session = session,
                onSaved = {
                    navController.navigate("login") {
                        popUpTo("config") { inclusive = true }
                    }
                }
            )
        }
        composable("login") {
            LoginScreen(
                api = api,
                onLoggedIn = {
                    navController.navigate("users") {
                        popUpTo("login") { inclusive = true }
                    }
                },
                onGoRegister = { navController.navigate("register") }
            )
        }
        composable("register") {
            RegisterScreen(
                api = api,
                onDone = { navController.popBackStack() }
            )
        }
        composable("users") {
            UserListScreen(
                api = api,
                session = session,
                onOpenChat = { isGroup, targetId, name ->
                    val encoded = java.net.URLEncoder.encode(name, "UTF-8")
                    navController.navigate("chat/$isGroup/$targetId/$encoded")
                },
                onLogout = {
                    context.stopService(Intent(context, MessagePollingService::class.java))
                    navController.navigate("login") {
                        popUpTo("users") { inclusive = true }
                    }
                }
            )
        }
        composable("chat/{isGroup}/{targetId}/{targetName}") { backStackEntry ->
            val isGroup = backStackEntry.arguments?.getString("isGroup")?.toBoolean() ?: false
            val targetId = backStackEntry.arguments?.getString("targetId")?.toLongOrNull() ?: -1L
            val targetName = backStackEntry.arguments?.getString("targetName")?.let {
                runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
            } ?: ""
            ChatScreen(
                api = api,
                session = session,
                isGroup = isGroup,
                targetId = targetId,
                targetName = targetName,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
