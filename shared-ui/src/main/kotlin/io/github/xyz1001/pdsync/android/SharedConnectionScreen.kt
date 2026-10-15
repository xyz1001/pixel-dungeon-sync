package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** One physical UI source, compiled by both desktop JVM and Android. */
@Composable
fun SharedConnectionScreen(controller: SharedConnectionController) {
    SharedConnectionTheme {
        SharedConnectionContent(controller)
    }
}

@Composable
private fun SharedConnectionContent(controller: SharedConnectionController) {
    val state = controller.state
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (state.origin == ConnectionEntryModel.Origin.SETUP) "3 / 4  连接云端" else "连接设置",
            style = MaterialTheme.typography.h5)
        val appreciation = state.appreciation
        if (appreciation != null) {
            appreciation.image?.let {
                Image(it, "赞赏码", Modifier.fillMaxWidth().height(240.dp), contentScale = ContentScale.Fit)
            }
            appreciation.username?.let { username ->
                Text(if (appreciation.authenticated) "用户名 $username" else "用户名（未验证） $username")
                Button(onClick = controller::copyUsername) { Text("复制用户名") }
                Text("赞赏时请备注用户名")
            }
            Text(appreciation.expiry)
            if (appreciation.accessStatus.isNotEmpty()) Text(appreciation.accessStatus)
            if (appreciation.message.isNotEmpty()) Text(appreciation.message)
            if (appreciation.busy) CircularProgressIndicator()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = controller::back) { Text("返回") }
                Button(onClick = controller::refreshAppreciation, enabled = appreciation.canRefresh) { Text("刷新") }
            }
            return@Column
        }
        if (state.mode == ConnectionEntryModel.Mode.CHOICE) {
            Button(onClick = { controller.choose(ConnectionEntryModel.Mode.OFFICIAL) }, modifier = Modifier.fillMaxWidth()) {
                Text("使用官方服务")
            }
            Button(onClick = { controller.choose(ConnectionEntryModel.Mode.CUSTOM) }, modifier = Modifier.fillMaxWidth()) {
                Text("自定义 WebDAV")
            }
        } else {
            if (state.mode == ConnectionEntryModel.Mode.CUSTOM) {
                OutlinedTextField(state.endpoint, controller::editEndpoint, modifier = Modifier.fillMaxWidth(),
                    label = { Text("WebDAV 地址") }, placeholder = { Text("https://example.com/dav") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, enabled = !state.busy)
            }
            OutlinedTextField(state.username, controller::editUsername, modifier = Modifier.fillMaxWidth(),
                label = { Text("用户名") }, singleLine = true, enabled = !state.busy)
            OutlinedTextField(state.password, controller::editPassword, modifier = Modifier.fillMaxWidth(),
                label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, enabled = !state.busy)
            if (state.mode == ConnectionEntryModel.Mode.OFFICIAL) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = controller::login, enabled = !state.busy) { Text("登录") }
                    Button(onClick = controller::register, enabled = !state.busy) { Text("注册") }
                }
                if (state.canRetestOfficial) {
                    Button(onClick = controller::testConnection, enabled = !state.busy) { Text("测试连接") }
                }
                Button(onClick = controller::openAppreciation, enabled = !state.busy && state.canAppreciate) {
                    Text(state.appreciationLabel)
                }
            } else Button(onClick = controller::testConnection, enabled = !state.busy) { Text("测试连接") }
            if (state.showChangeService) TextButton(onClick = controller::showChooser, enabled = !state.busy) { Text("更换服务") }
        }
        if (state.recoveryMessage.isNotEmpty()) Text(state.recoveryMessage)
        if (state.recoveryLabel.isNotEmpty()) {
            TextButton(onClick = controller::reloadSavedConnection, enabled = !state.busy) { Text(state.recoveryLabel) }
        }
        if (state.notice.isNotEmpty()) Text(state.notice,
            color = if (state.error) MaterialTheme.colors.error else MaterialTheme.colors.onSurface)
        if (state.busy) {
            CircularProgressIndicator()
            if (state.canCancel) TextButton(onClick = controller::cancel) { Text("取消") }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = controller::back, enabled = !state.busy) {
                Text(if (state.origin == ConnectionEntryModel.Origin.SETUP) "上一步" else "返回")
            }
            if (state.mode != ConnectionEntryModel.Mode.CHOICE) {
                Button(onClick = controller::continueConnection, enabled = state.canContinue) {
                    Text(if (state.origin == ConnectionEntryModel.Origin.SETUP) "下一步" else "保存")
                }
            }
        }
    }
}
