@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.musicly.musicly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicly.musicly.tg.TgAuth
import com.musicly.musicly.tg.TgBackend
import com.musicly.musicly.tg.TgUserState
import kotlinx.coroutines.delay

private val Red = Color(0xFFE5484D)
private val Green = Color(0xFF30A46C)

/** The backend behind the user's own Telegram account (the real one, or a fake in tests). */
val Env.tgb: TgBackend get() = library.tgUser.backend ?: error("The Telegram account isn't set up in this app.")

/** Opens the chat picker when logged in, otherwise the login steps. */
fun openTgAccount(env: Env) {
    if (env.library.tgUser.loggedIn) openPage(env, "tg-chats") { TgChatsScreen() }
    else openPage(env, "tg-login") { TgLoginScreen() }
}

/**
 * Guided login with the user's own Telegram account: their own app key, the
 * phone number, the code Telegram sends, and the 2-step password if they have
 * one. Every step is driven by the backend's auth state.
 */
@Composable
fun TgLoginScreen() {
    val env = LocalEnv.current
    val user = env.library.tgUser
    val p = palette()
    val tg = env.tgb
    val auth by tg.auth.collectAsState()

    var keyId by remember { mutableStateOf("") }
    var keyHash by remember { mutableStateOf("") }
    var editKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(user.apiKey() != null) }
    val actionError = user.error

    // Every new step from Telegram ends the "working…" state.
    LaunchedEffect(auth) { busy = false }

    // A key is saved but TDLib isn't running yet (e.g. after logging out): start it.
    LaunchedEffect(auth is TgAuth.Off, hasKey) {
        if (auth is TgAuth.Off && hasKey && !editKey) user.begin()
    }

    // Logged in: say so, then show the chats to pick from.
    LaunchedEffect(auth is TgAuth.Ready) {
        if (auth is TgAuth.Ready) {
            delay(900)
            env.nav.pop()
            openPage(env, "tg-chats") { TgChatsScreen() }
        }
    }

    val needKey = editKey || (auth is TgAuth.Off && !hasKey)

    fun run(block: suspend TgBackend.() -> Unit) {
        busy = true
        user.clearError()
        user.act(block).invokeOnCompletion { busy = false }
    }

    Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp), contentAlignment = Alignment.CenterStart) {
            AppIconButton(AppIcons.back, onClick = { env.nav.pop() })
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp).navigationBarsPadding(),
        ) {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(p.ink),
                contentAlignment = Alignment.Center,
            ) { Icon(AppIcons.telegramOn, size = 36.dp, tint = p.onInk) }
            Spacer(Modifier.height(18.dp))

            when {
                needKey -> {
                    Head("Your Telegram account", "Play songs from every channel and group you're in, not just the ones you run. First, a free key of your own.")
                    KeyStep(
                        keyId, { keyId = it.filter(Char::isDigit).take(10) }, keyHash, { keyHash = it.trim().take(64) },
                        busy = busy,
                        onOpen = { env.openUrl?.invoke("https://my.telegram.org") },
                        onContinue = {
                            val id = keyId.toIntOrNull() ?: return@KeyStep
                            hasKey = true
                            editKey = false
                            run { user.begin(id, keyHash) }
                        },
                    )
                }
                auth is TgAuth.Off || auth is TgAuth.Starting -> {
                    Head("Your Telegram account", "Starting Telegram…")
                    Spinner("Just a moment")
                    if (actionError != null) Failure(actionError, onRetry = { run { user.begin() } }, onChangeKey = { editKey = true }, hideKeyOption = user.builtInKey != null)
                }
                auth is TgAuth.WaitPhone -> {
                    val a = auth as TgAuth.WaitPhone
                    PhoneStep(a.error ?: actionError, busy) { phone -> run { submitPhone(phone) } }
                }
                auth is TgAuth.WaitCode -> {
                    val a = auth as TgAuth.WaitCode
                    CodeStep(a, a.error ?: actionError, busy, onCode = { run { submitCode(it) } }, onResend = { run { resendCode() } })
                }
                auth is TgAuth.WaitPassword -> {
                    val a = auth as TgAuth.WaitPassword
                    PasswordStep(a, a.error ?: actionError, busy) { run { submitPassword(it) } }
                }
                auth is TgAuth.Ready -> {
                    val a = auth as TgAuth.Ready
                    Head("You're in", "Logged in as ${a.name}")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(26.dp).clip(CircleShape).background(Green), contentAlignment = Alignment.Center) {
                            Icon(AppIcons.check, size = 15.dp, tint = Color.White)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("Now pick the channels to read songs from.", color = p.sub, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    }
                }
                auth is TgAuth.Failed -> {
                    val a = auth as TgAuth.Failed
                    Head("Your Telegram account", "That didn't work.")
                    Failure(a.message, onRetry = { run { user.begin() } }, onChangeKey = { editKey = true }, hideKeyOption = user.builtInKey != null)
                }
            }
            if (!needKey && (auth is TgAuth.WaitPhone || auth is TgAuth.WaitCode || auth is TgAuth.WaitPassword || auth is TgAuth.Off)) {
                Spacer(Modifier.height(24.dp))
            }
            if (!(auth is TgAuth.Ready)) {
                Spacer(Modifier.height(8.dp))
                Note("Musicly only reads the channels you pick. It never posts, joins or leaves anything.")
            }
        }
    }
}

// -- steps --------------------------------------------------------------------------------

@Composable
private fun Head(title: String, text: String) {
    val p = palette()
    Text(title, color = p.ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
    Spacer(Modifier.height(6.dp))
    Text(text, color = p.sub, fontSize = 14.sp, lineHeight = 20.sp)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun KeyStep(
    id: String, onId: (String) -> Unit, hash: String, onHash: (String) -> Unit,
    busy: Boolean, onOpen: () -> Unit, onContinue: () -> Unit,
) {
    val p = palette()
    val valid = id.toIntOrNull()?.let { it > 0 } == true && hash.length >= 16
    Text("Your app key", color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        "It's free and takes 2 minutes. Open my.telegram.org, log in with your phone number, choose " +
            "\"API development tools\" and create an app (any name works). Telegram then shows an api_id and an api_hash. " +
            "It has to be your own key. It stays on this phone and is never sent anywhere but Telegram.",
        color = p.sub, fontSize = 14.sp, lineHeight = 20.sp,
    )
    Spacer(Modifier.height(14.dp))
    Row { PillButton(AppIcons.external, "Open my.telegram.org", onOpen, filled = false) }
    Spacer(Modifier.height(20.dp))
    TgField(id, onId, "api_id (numbers)", AppIcons.key, KeyboardType.Number)
    Spacer(Modifier.height(12.dp))
    TgField(hash, onHash, "api_hash (letters and numbers)", AppIcons.key, KeyboardType.Ascii, password = true, onSubmit = { if (valid) onContinue() })
    Spacer(Modifier.height(16.dp))
    PillButton(AppIcons.chevron, if (busy) "Starting…" else "Continue", if (valid && !busy) onContinue else null, Modifier.fillMaxWidth())
}

@Composable
private fun PhoneStep(error: String?, busy: Boolean, onSubmit: (String) -> Unit) {
    var phone by remember { mutableStateOf("") }
    val p = palette()
    Head("Your phone number", "Use the international format with your country code. Telegram sends a login code to the Telegram app on your other devices, or by SMS.")
    TgField(phone, { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(20) }, "+251…", AppIcons.phone, KeyboardType.Phone, onSubmit = { if (phone.isNotBlank()) onSubmit(phone) })
    ErrorLine(error)
    Spacer(Modifier.height(16.dp))
    PillButton(AppIcons.chevron, if (busy) "Sending code…" else "Send code", if (phone.length >= 6 && !busy) { { onSubmit(phone) } } else null, Modifier.fillMaxWidth())
}

@Composable
private fun CodeStep(a: TgAuth.WaitCode, error: String?, busy: Boolean, onCode: (String) -> Unit, onResend: () -> Unit) {
    var code by remember { mutableStateOf("") }
    val p = palette()
    Head("Enter the code", "Telegram sent a ${a.length}-digit code to ${a.phone} by ${a.where}. It can take a minute.")
    TgField(
        code, { v ->
            code = v.filter(Char::isDigit).take(a.length)
        },
        "Code", AppIcons.key, KeyboardType.NumberPassword, onSubmit = { if (code.isNotBlank()) onCode(code) },
    )
    ErrorLine(error)
    Spacer(Modifier.height(16.dp))
    PillButton(AppIcons.chevron, if (busy) "Checking…" else "Log in", if (code.length >= 3 && !busy) { { onCode(code) } } else null, Modifier.fillMaxWidth())
    val wait = a.resendInSec
    if (wait != null) {
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50)).clickable(enabled = wait <= 0 && !busy, onClick = onResend)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (wait > 0) "Send the code again in ${wait}s" else "Send the code again",
                color = if (wait > 0) p.sub else p.ink, fontWeight = FontWeight.Medium, fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun PasswordStep(a: TgAuth.WaitPassword, error: String?, busy: Boolean, onSubmit: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val p = palette()
    Head("Two-step password", "Your account has an extra password. This is the one you set in Telegram under Settings, Privacy, Two-Step Verification (not the login code).")
    TgField(
        pw, { pw = it }, "Password", AppIcons.key, KeyboardType.Password, password = !show,
        trailing = { AppIconButton(if (show) AppIcons.eyeOff else AppIcons.eye, onClick = { show = !show }, iconSize = 18.dp, tint = p.sub) },
        onSubmit = { if (pw.isNotEmpty()) onSubmit(pw) },
    )
    if (!a.hint.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        Text("Hint: ${a.hint}", color = p.sub, fontSize = 14.sp)
    }
    ErrorLine(error)
    Spacer(Modifier.height(16.dp))
    PillButton(AppIcons.chevron, if (busy) "Checking…" else "Log in", if (pw.isNotEmpty() && !busy) { { onSubmit(pw) } } else null, Modifier.fillMaxWidth())
}

@Composable
private fun Failure(message: String, onRetry: () -> Unit, onChangeKey: () -> Unit, hideKeyOption: Boolean = false) {
    val p = palette()
    Text(message, color = Red, fontSize = 14.sp, lineHeight = 20.sp)
    Spacer(Modifier.height(16.dp))
    PillButton(AppIcons.refresh, "Try again", onRetry, Modifier.fillMaxWidth())
    Spacer(Modifier.height(4.dp))
    if (!hideKeyOption) Box(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50)).clickable(onClick = onChangeKey).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text("Use a different app key", color = p.ink, fontWeight = FontWeight.Medium, fontSize = 14.sp) }
}

@Composable
private fun Spinner(text: String) {
    val p = palette()
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), color = p.ink, strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, color = p.sub, fontSize = 14.sp)
    }
}

@Composable
private fun ErrorLine(error: String?) {
    if (error == null) return
    Text(error, color = Red, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun Note(text: String) {
    val p = palette()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(AppIcons.check, size = 16.dp, tint = p.ink, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = p.sub, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
    }
}

/** Rounded text field with a leading icon, for numbers, phone numbers and passwords. */
@Composable
internal fun TgField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    icon: Ph,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onSubmit: (() -> Unit)? = null,
) {
    val p = palette()
    BasicTextField(
        value, onChange, Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = TextStyle(color = p.ink, fontSize = 16.sp, fontFamily = Inter),
        cursorBrush = SolidColor(p.ink),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false, imeAction = ImeAction.Done,
            capitalization = KeyboardCapitalization.None, keyboardType = keyboard,
        ),
        keyboardActions = KeyboardActions(onAny = { onSubmit?.invoke() }),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).background(p.card).padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, size = 20.dp, tint = p.sub)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(hint, color = p.sub, fontSize = 16.sp, maxLines = 1)
                    inner()
                }
                if (trailing != null) trailing()
            }
        },
    )
}
