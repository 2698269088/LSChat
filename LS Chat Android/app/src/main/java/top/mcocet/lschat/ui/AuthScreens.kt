package top.mcocet.lschat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.mcocet.lschat.data.ApiClient

@Composable
fun LoginScreen(
    api: ApiClient,
    onLoggedIn: () -> Unit,
    onGoRegister: () -> Unit
) {
    var userIdText by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeContentPadding()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("登录 LS Chat", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = userIdText,
            onValueChange = { userIdText = it.filter(Char::isDigit) },
            label = { Text("用户 ID") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
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

        Button(
            onClick = {
                if (loading) return@Button
                val userId = userIdText.toLongOrNull()
                when {
                    userId == null || userId <= 0 -> error = "请输入有效的用户 ID"
                    password.isEmpty() -> error = "请输入密码"
                    else -> {
                        scope.launch {
                            loading = true
                            error = null
                            try {
                                api.login(userId, password)
                                onLoggedIn()
                            } catch (e: Exception) {
                                error = e.message ?: "登录失败"
                            } finally {
                                loading = false
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (loading) "正在登录..." else "登录")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onGoRegister, modifier = Modifier.fillMaxWidth()) {
            Text("没有账号？去注册")
        }
    }
}

@Composable
fun RegisterScreen(
    api: ApiClient,
    onDone: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var registeredId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeContentPadding()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("注册新账号", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it.trim() },
            label = { Text("用户名（3-32 位字母/数字/下划线，可重名）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码（6-64 位）") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text("确认密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
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

        Button(
            onClick = {
                if (loading) return@Button
                when {
                    username.isEmpty() || password.isEmpty() -> error = "请输入完整信息"
                    password != confirm -> error = "两次输入的密码不一致"
                    else -> {
                        scope.launch {
                            loading = true
                            error = null
                            try {
                                val user = api.register(username, password)
                                registeredId = user.id
                            } catch (e: Exception) {
                                error = e.message ?: "注册失败"
                            } finally {
                                loading = false
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (loading) "正在注册..." else "注册")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("已有账号？返回登录")
        }
    }

    // 注册成功后弹出用户 ID 提示（登录需使用 ID）
    registeredId?.let { id ->
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text("注册成功") },
            text = { Text("你的用户 ID 是 $id，\n登录时需要使用此 ID，请牢记。") },
            confirmButton = {
                TextButton(onClick = onDone) { Text("确定") }
            }
        )
    }
}
