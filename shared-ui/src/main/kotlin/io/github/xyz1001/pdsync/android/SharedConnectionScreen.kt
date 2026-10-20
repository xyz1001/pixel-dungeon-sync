package io.github.xyz1001.pdsync.android

import pdsync.i18n.SharedMessages
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.Surface
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.IconButton
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    LaunchedEffect(controller, state.showInlineAppreciation, state.appreciation == null) {
        if (state.showInlineAppreciation && state.appreciation == null) controller.openInlineAppreciation()
    }
    val appreciation = state.appreciation.takeUnless { state.appreciationInline }
    SharedAppShell(
        title = if (appreciation != null) state.appreciationLabel else if (setup) SharedMessages.CONNECT_CLOUD.text() else SharedMessages.CONNECTION_SETTINGS.text(),
        eyebrow = if (setup && appreciation == null) SharedMessages.STEP_TWO.text() else null,
        game = game,
        onboardingStep = if (setup) 2 else null,
        footer = {
            SharedActionButton(
                label = if (appreciation != null) SharedMessages.BACK.text() else if (setup) SharedMessages.BACK_STEP.text() else SharedMessages.BACK_SETTINGS.text(),
                onClick = controller::back,
                enabled = appreciation != null || state.canGoBack,
                style = SharedButtonStyle.TEXT,
            )
            Spacer(Modifier.weight(1f))
            if (appreciation == null) {
                SharedActionButton(if (setup) SharedMessages.FINISH_SETUP.text() else SharedMessages.SAVE_RETURN.text(), controller::continueConnection,
                    enabled = state.canContinue)
            }
        },
    ) {
        if (appreciation != null) {
            SharedForm {
                SharedAppreciationContent(appreciation, controller::copyUsername, onSaveImage, imageSaveBusy, imageSaveMessage,
                    refresh = controller::refreshAppreciation)
            }
        } else {
            when (state.mode) {
                ConnectionEntryModel.Mode.CHOICE -> SharedServiceChoices(controller)
                ConnectionEntryModel.Mode.OFFICIAL -> {
                    SharedBuiltinServiceDisclosure()
                    if (state.showOfficialAccount) {
                        SharedOfficialAccountPanel(state, controller::copyUsername,
                            controller::openAppreciation, onSaveImage, imageSaveBusy, imageSaveMessage, controller::refreshAppreciation)
                    } else {
                        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
                            SharedCredentialFields(state, controller)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SharedActionButton(SharedMessages.SIGN_IN.text(), controller::login, enabled = !state.busy)
                                SharedActionButton(SharedMessages.REGISTER.text(), controller::register, enabled = !state.busy,
                                    style = SharedButtonStyle.SECONDARY)
                            }
                        }
                    }
                }
                ConnectionEntryModel.Mode.CUSTOM -> Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
                    // Only editable DAV probes are tests; a commit has a different real notice.
                    val testing = state.busy && state.canEdit
                    SharedTextField(SharedMessages.WEBDAV_ADDRESS.text(), state.endpoint, controller::editEndpoint,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = state.canEdit)
                    SharedCredentialFields(state, controller)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SharedActionButton(if (testing) SharedMessages.TESTING.text() else SharedMessages.TEST_CONNECTION.text(), controller::testConnection,
                            enabled = !state.busy, style = SharedButtonStyle.SECONDARY)
                        if (testing) Text(SharedMessages.SENDING_TEST.text(), Modifier.weight(1f),
                            color = SharedUiColors.muted, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
            if (state.mode != ConnectionEntryModel.Mode.CHOICE && (state.showChangeService || setup)) {
                Spacer(Modifier.height(16.dp))
                SharedActionButton(SharedMessages.SWITCH_SERVICE.text(), controller::showChooser, enabled = !state.busy,
                    style = SharedButtonStyle.TEXT)
            }
            if (state.recoveryMessage.isNotEmpty()) SharedConnectionNotice(state.recoveryMessage)
            if (state.recoveryLabel.isNotEmpty()) {
                SharedActionButton(state.recoveryLabel, controller::reloadSavedConnection, enabled = !state.busy,
                    style = SharedButtonStyle.TEXT)
            }
            if (state.notice.isNotEmpty() && !(state.mode == ConnectionEntryModel.Mode.CUSTOM && state.busy && state.canEdit))
                SharedConnectionNotice(state.notice, state.error)
            if (state.busy) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    if (state.canCancel) SharedActionButton(SharedMessages.CANCEL_OPERATION.text(), controller::cancel, style = SharedButtonStyle.TEXT)
                }
            }
        }
    }
}

@Composable
private fun SharedCredentialFields(state: SharedConnectionState, controller: SharedConnectionController) {
    val official = state.mode == ConnectionEntryModel.Mode.OFFICIAL
    // Visibility is transient UI state, never saved with credentials or across identities.
    var passwordVisible by remember(controller, state.mode, state.username, state.endpoint) { mutableStateOf(false) }
    val hiddenPassword = remember { PasswordVisualTransformation() }
    val passwordKeyboard = remember { KeyboardOptions(keyboardType = KeyboardType.Password) }
    SharedTextField(SharedMessages.USERNAME.text(), state.username, controller::editUsername, enabled = state.canEdit,
        placeholder = if (official) SharedMessages.REGISTER_USERNAME_HINT.text() else SharedMessages.PROVIDER_USERNAME.text())
    SharedTextField(SharedMessages.PASSWORD_CREDENTIAL.text(), state.password, controller::editPassword,
        visualTransformation = if (passwordVisible) VisualTransformation.None else hiddenPassword,
        keyboardOptions = passwordKeyboard, enabled = state.canEdit,
        placeholder = if (official) SharedMessages.REGISTER_PASSWORD_HINT.text() else SharedMessages.PASSWORD_HINT.text(),
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }, enabled = state.canEdit,
                modifier = Modifier.size(24.dp).focusProperties { canFocus = false }.semantics {
                    contentDescription = if (passwordVisible) SharedMessages.HIDE_PASSWORD.text() else SharedMessages.SHOW_PASSWORD.text()
                }) {
                SharedReferenceIcon(if (passwordVisible) "visibility-off" else "visibility")
            }
        })
}

@Composable
private fun SharedServiceChoices(controller: SharedConnectionController) {
    Text(SharedMessages.CLOUD_SERVICE_HINT.text(),
        color = SharedUiColors.text, fontSize = 15.sp, lineHeight = 22.5.sp)
    Spacer(Modifier.height(20.dp))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val builtin: @Composable (Modifier) -> Unit = { m ->
            SharedServiceCard(SharedMessages.BUILT_IN_SERVICE.text(), SharedMessages.BUILT_IN_DESCRIPTION.text(),
                SharedMessages.USE_BUILT_IN.text(), "cloud", { controller.choose(ConnectionEntryModel.Mode.OFFICIAL) }, m)
        }
        val custom: @Composable (Modifier) -> Unit = { m ->
            SharedServiceCard(SharedMessages.CUSTOM_WEBDAV.text(), SharedMessages.WEBDAV_DESCRIPTION.text(),
                SharedMessages.CONFIGURE_WEBDAV.text(), "sync", { controller.choose(ConnectionEntryModel.Mode.CUSTOM) }, m)
        }
        if (maxWidth <= 558.dp) Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            builtin(Modifier.fillMaxWidth()); custom(Modifier.fillMaxWidth())
        } else Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            builtin(Modifier.weight(1f).fillMaxHeight()); custom(Modifier.weight(1f).fillMaxHeight())
        }
    }
    Spacer(Modifier.height(20.dp))
}

@Composable
internal fun SharedBuiltinServiceDisclosure() {
    Surface(Modifier.fillMaxWidth(), color = SharedUiColors.secondary, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SharedUiColors.line), elevation = 1.dp) {
        Text(buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(SharedMessages.SERVICE_DETAILS.text()) }
            append(SharedMessages.SERVICE_COST_INTRO.text())
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(SharedMessages.FREE_TRIAL.text()) }
            append(SharedMessages.SERVICE_COST_END.text())
        }, Modifier.padding(horizontal = 20.dp, vertical = 16.dp), color = SharedUiColors.label,
            fontSize = 13.sp, lineHeight = 20.8.sp)
    }
    Spacer(Modifier.height(20.dp))
}

/** Account facts and QR bytes come only from the authenticated controller; no demo entitlement. */
@Composable
internal fun SharedOfficialAccountPanel(
    state: SharedConnectionState,
    copyUsername: () -> Unit,
    openAppreciation: () -> Unit,
    onSaveImage: (() -> Unit)?,
    imageSaveBusy: Boolean,
    imageSaveMessage: String,
    refresh: () -> Unit,
) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colors.surface, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SharedUiColors.line), elevation = 1.dp) {
        Column(Modifier.padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(state.username, fontSize = 18.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
                    if (state.officialExpiry.isNotBlank()) Text(state.officialExpiry, Modifier.padding(top = 6.dp),
                        color = SharedUiColors.muted, fontSize = 13.sp, lineHeight = 19.5.sp)
                }
                val accessible = state.officialAuthenticated && !state.officialExpired && state.officialAccessStatus.isBlank()
                Text(state.officialAccessStatus.ifBlank { if (accessible) SharedMessages.ACCESSIBLE.text() else SharedMessages.AWAITING_VERIFICATION.text() },
                    Modifier.widthIn(max = 116.dp).background(
                        if (accessible) Color(0xFFC4EED5) else if (state.officialExpired) Color(0xFFFFDAD6) else SharedUiColors.secondary,
                        RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
                    color = if (accessible) Color(0xFF002111) else if (state.officialExpired) Color(0xFF410002) else SharedUiColors.label,
                    fontSize = 11.sp, lineHeight = 16.5.sp, fontWeight = FontWeight.Bold)
            }
            if (state.showInlineAppreciation) {
                Spacer(Modifier.height(36.dp))
                SharedAccountAppreciation(state, copyUsername, onSaveImage, imageSaveBusy, imageSaveMessage, refresh)
            } else {
                Spacer(Modifier.height(16.dp))
                SharedActionButton(state.appreciationLabel, openAppreciation,
                    enabled = !state.busy && state.canAppreciate, style = SharedButtonStyle.TEXT)
            }
        }
    }
}

@Composable
private fun SharedAccountAppreciation(
    state: SharedConnectionState,
    copyUsername: () -> Unit,
    onSaveImage: (() -> Unit)?,
    imageSaveBusy: Boolean,
    imageSaveMessage: String,
    refresh: () -> Unit,
) {
    val appreciation = state.appreciation
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val panelWidth = maxWidth
        val small = panelWidth < 260.dp
        val code: @Composable () -> Unit = {
            Surface(Modifier.width(minOf(110.dp, panelWidth)).height(110.dp), border = BorderStroke(1.dp, SharedUiColors.fieldBorder),
                shape = RoundedCornerShape(12.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    if (appreciation?.image != null) SharedAppreciationImage(appreciation.image,
                        Modifier.fillMaxSize().padding(5.dp))
                    else if (appreciation == null || appreciation.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text(SharedMessages.SUPPORT_CODE_UNAVAILABLE.text(), Modifier.padding(10.dp), color = SharedUiColors.muted,
                        fontSize = 11.sp, lineHeight = 16.5.sp)
                }
            }
        }
        val actions: @Composable () -> Unit = {
            val accountButtons: @Composable (Modifier, Modifier) -> Unit = { saveModifier, copyModifier ->
                if (onSaveImage != null) SharedActionButton(if (imageSaveBusy) SharedMessages.SAVING.text() else SharedMessages.SAVE_SUPPORT_CODE.text(), onSaveImage,
                    modifier = saveModifier,
                    enabled = appreciation?.image != null && !appreciation.busy && !imageSaveBusy,
                    style = SharedButtonStyle.SECONDARY)
                SharedActionButton(SharedMessages.COPY_USERNAME.text(), copyUsername,
                    modifier = copyModifier,
                    enabled = appreciation?.username != null, style = SharedButtonStyle.TEXT)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(SharedMessages.SUPPORT_USERNAME_NOTE.text(state.username), color = SharedUiColors.muted,
                    fontSize = 12.sp, lineHeight = 18.sp)
                if (small) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        accountButtons(Modifier, Modifier)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val compact = panelWidth < 400.dp
                        accountButtons(
                            if (compact) Modifier.weight(1.3f) else Modifier,
                            if (compact && onSaveImage != null) Modifier.weight(1f) else Modifier)
                    }
                }
                SharedActionButton(SharedMessages.REFRESH.text(), refresh,
                    enabled = appreciation?.canRefresh == true && !appreciation.busy, style = SharedButtonStyle.SECONDARY)
                if (appreciation?.message?.isNotBlank() == true) SharedConnectionNotice(appreciation.message)
                if (onSaveImage != null && imageSaveMessage.isNotBlank()) SharedConnectionNotice(imageSaveMessage)
            }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            code()
            Box(Modifier.fillMaxWidth()) { actions() }
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
    refresh: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        appreciation.image?.let {
            SharedAppreciationImage(it, Modifier.fillMaxWidth().height(imageHeight))
        }
        if (onSaveImage != null) {
            SharedActionButton(if (imageSaveBusy) SharedMessages.SAVING.text() else SharedMessages.SAVE_IMAGE.text(), onSaveImage,
                enabled = appreciation.image != null && !appreciation.busy && !imageSaveBusy,
                style = SharedButtonStyle.SECONDARY)
            if (imageSaveMessage.isNotEmpty()) SharedConnectionNotice(imageSaveMessage)
        }
        if (showAccount) appreciation.username?.let { username ->
            Text(if (appreciation.authenticated) SharedMessages.USERNAME_VALUE.text(username) else SharedMessages.UNVERIFIED_USERNAME.text(username))
            SharedActionButton(SharedMessages.COPY_USERNAME.text(), copyUsername, style = SharedButtonStyle.SECONDARY)
            Text(SharedMessages.SUPPORT_INCLUDE_USERNAME.text(), color = SharedUiColors.muted, style = MaterialTheme.typography.body2)
        }
        if (showAccount) {
            Text(appreciation.expiry, color = SharedUiColors.muted, style = MaterialTheme.typography.body2)
            if (appreciation.accessStatus.isNotEmpty()) Text(appreciation.accessStatus, style = MaterialTheme.typography.body2)
            if (appreciation.message.isNotEmpty()) SharedConnectionNotice(appreciation.message)
        } else if (refresh != null && appreciation.message.isNotEmpty()) {
            SharedConnectionNotice(appreciation.message)
        } else if (appreciation.image == null && !appreciation.busy) {
            // The inline panel keeps account facts hidden.
            SharedConnectionNotice(SharedMessages.SUPPORT_CODE_UNAVAILABLE.text())
        }
        if (refresh != null) SharedActionButton(SharedMessages.REFRESH.text(), refresh,
            enabled = appreciation.canRefresh && !appreciation.busy, style = SharedButtonStyle.SECONDARY)
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

@Composable
private fun SharedServiceCard(title: String, description: String, label: String, icon: String,
    action: () -> Unit, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), border = BorderStroke(2.dp, SharedUiColors.line)) {
        Column(Modifier.padding(26.dp).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SharedReferenceIcon(icon, Modifier.size(18.dp))
                Text(title, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(description, fontSize = 13.sp, lineHeight = 20.8.sp, color = SharedUiColors.label)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(20.dp))
            SharedActionButton(label, action, Modifier.fillMaxWidth(),
                style = if (icon == "cloud") SharedButtonStyle.PRIMARY else SharedButtonStyle.SECONDARY)
        }
    }
}
