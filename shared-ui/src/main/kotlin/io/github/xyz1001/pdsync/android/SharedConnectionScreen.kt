package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One physical UI source, compiled by both desktop JVM and Android. */
@Composable
fun SharedConnectionScreen(
    controller: SharedConnectionController,
    game: SharedGameIdentity? = null,
    onSaveImage: (() -> Unit)? = null,
    imageSaveBusy: Boolean = false,
    imageSaveMessage: String = "",
) {
    SharedConnectionTheme {
        SharedConnectionContent(controller, game, onSaveImage, imageSaveBusy, imageSaveMessage)
    }
}

@Composable
private fun SharedConnectionContent(
    controller: SharedConnectionController,
    game: SharedGameIdentity?,
    onSaveImage: (() -> Unit)?,
    imageSaveBusy: Boolean,
    imageSaveMessage: String,
) {
    val state = controller.state
    LaunchedEffect(controller, state.showOfficialAccount) {
        while (state.showOfficialAccount) {
            delay(1_000)
            controller.refreshAccountStatus()
        }
    }
    val setup = state.origin == ConnectionEntryModel.Origin.SETUP
    LaunchedEffect(controller, state.officialExpired) {
        if (setup && state.officialExpired) controller.openInlineAppreciation()
    }
    val appreciation = state.appreciation.takeUnless { state.appreciationInline }
    SharedAppShell(
        title = if (appreciation != null) state.appreciationLabel else if (setup) "连接云端" else "连接设置",
        eyebrow = if (setup && appreciation == null) "3 / 4" else null,
        game = game,
        onboardingStep = if (setup) 3 else null,
        footer = {
            SharedActionButton(
                label = if (setup && appreciation == null) "上一步" else "返回",
                onClick = controller::back,
                enabled = appreciation != null || state.canGoBack,
                style = SharedButtonStyle.TEXT,
            )
            Spacer(Modifier.weight(1f))
            if (appreciation == null && state.mode != ConnectionEntryModel.Mode.CHOICE) {
                SharedActionButton(if (setup) "下一步" else "保存", controller::continueConnection,
                    enabled = state.canContinue)
            }
        },
    ) {
        SharedForm {
            if (appreciation != null) {
                SharedAppreciationContent(appreciation, controller::copyUsername, onSaveImage, imageSaveBusy, imageSaveMessage)
            } else {
                if (state.mode == ConnectionEntryModel.Mode.CHOICE) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SharedActionButton("使用内置服务", { controller.choose(ConnectionEntryModel.Mode.OFFICIAL) },
                            modifier = Modifier.fillMaxWidth(), style = SharedButtonStyle.SECONDARY)
                        SharedActionButton("自定义 WebDAV", { controller.choose(ConnectionEntryModel.Mode.CUSTOM) },
                            modifier = Modifier.fillMaxWidth(), style = SharedButtonStyle.SECONDARY)
                    }
                } else {
                    if (state.mode == ConnectionEntryModel.Mode.CUSTOM) {
                        SharedTextField("WebDAV 地址", state.endpoint, controller::editEndpoint,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = state.canEdit)
                    }
                    if (state.mode == ConnectionEntryModel.Mode.CUSTOM || !state.showOfficialAccount) {
                        SharedTextField("用户名", state.username, controller::editUsername, enabled = state.canEdit)
                        SharedTextField("密码", state.password, controller::editPassword,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = state.canEdit)
                    }
                    if (state.mode == ConnectionEntryModel.Mode.OFFICIAL) {
                        if (state.showOfficialAccount) {
                            Text("用户名 ${state.username}")
                            Text(state.officialExpiry, color = SharedUiColors.muted, style = MaterialTheme.typography.body2)
                            if (state.officialAccessStatus.isNotEmpty()) SharedConnectionNotice(state.officialAccessStatus)
                            if (setup && state.officialExpired) {
                                SharedInlineAppreciationContent(state.appreciation, state.busy || state.appreciation == null,
                                    controller::copyUsername, onSaveImage = onSaveImage,
                                    imageSaveBusy = imageSaveBusy, imageSaveMessage = imageSaveMessage)
                            }
                            if (!setup) Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SharedActionButton(state.appreciationLabel, controller::openAppreciation,
                                    enabled = !state.busy && state.canAppreciate, style = SharedButtonStyle.TEXT)
                                Text("赞赏可续期一年", modifier = Modifier.weight(1f),
                                    color = SharedUiColors.muted, style = MaterialTheme.typography.body2)
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SharedActionButton("登录", controller::login, enabled = !state.busy,
                                    style = SharedButtonStyle.SECONDARY)
                                SharedActionButton("注册", controller::register, enabled = !state.busy,
                                    style = SharedButtonStyle.SECONDARY)
                            }
                        }
                    } else {
                        SharedActionButton("测试连接", controller::testConnection, enabled = !state.busy,
                            style = SharedButtonStyle.SECONDARY)
                    }
                    if (state.showChangeService) {
                        Spacer(Modifier.height(8.dp))
                        SharedActionButton("更换服务", controller::showChooser, enabled = !state.busy,
                            style = SharedButtonStyle.TEXT)
                    }
                }
                if (state.recoveryMessage.isNotEmpty()) SharedConnectionNotice(state.recoveryMessage)
                if (state.recoveryLabel.isNotEmpty()) {
                    SharedActionButton(state.recoveryLabel, controller::reloadSavedConnection, enabled = !state.busy,
                        style = SharedButtonStyle.TEXT)
                }
                if (state.notice.isNotEmpty()) SharedConnectionNotice(state.notice, state.error)
                if (state.busy) {
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        if (state.canCancel) SharedActionButton("取消", controller::cancel, style = SharedButtonStyle.TEXT)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SharedAppreciationContent(
    appreciation: SharedAppreciationState,
    copyUsername: () -> Unit,
    onSaveImage: (() -> Unit)? = null,
    imageSaveBusy: Boolean = false,
    imageSaveMessage: String = "",
    showAccount: Boolean = true,
    imageHeight: Dp = 240.dp,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        appreciation.image?.let {
            Image(it, "赞赏码", Modifier.fillMaxWidth().height(imageHeight), contentScale = ContentScale.Fit)
        }
        if (onSaveImage != null) {
            SharedActionButton(if (imageSaveBusy) "保存中…" else "保存图片", onSaveImage,
                enabled = appreciation.image != null && !appreciation.busy && !imageSaveBusy,
                style = SharedButtonStyle.SECONDARY)
            if (imageSaveMessage.isNotEmpty()) SharedConnectionNotice(imageSaveMessage)
        }
        if (showAccount) appreciation.username?.let { username ->
            Text(if (appreciation.authenticated) "用户名 $username" else "用户名（未验证） $username")
            SharedActionButton("复制用户名", copyUsername, style = SharedButtonStyle.SECONDARY)
            Text("赞赏时请备注用户名", color = SharedUiColors.muted, style = MaterialTheme.typography.body2)
        }
        if (showAccount) {
            Text(appreciation.expiry, color = SharedUiColors.muted, style = MaterialTheme.typography.body2)
            if (appreciation.accessStatus.isNotEmpty()) Text(appreciation.accessStatus, style = MaterialTheme.typography.body2)
            if (appreciation.message.isNotEmpty()) SharedConnectionNotice(appreciation.message)
        } else if (appreciation.image == null && !appreciation.busy) {
            // The inline panel does not disclose account metadata or account-refresh errors.
            SharedConnectionNotice("赞赏码暂时无法加载。")
        }
        if (appreciation.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun SharedConnectionNotice(message: String, error: Boolean = false) {
    val block = Modifier.fillMaxWidth().padding(top = 14.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }
    Text(message,
        modifier = if (error) block.background(SharedUiColors.errorBackground, RoundedCornerShape(10.dp))
            .padding(horizontal = 15.dp, vertical = 12.dp) else block,
        color = if (error) MaterialTheme.colors.error else SharedUiColors.muted,
        style = MaterialTheme.typography.body2)
}
