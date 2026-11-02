package com.taptrack.app.ui.screens.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.taptrack.app.AppContainer
import com.taptrack.app.ui.theme.Web
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

private enum class AuthPage { LOGIN, JOIN, FORGOT }

/** Signed out: sign in, open an account (invite code), or reset a forgotten password. */
@Composable
fun AuthScreens(container: AppContainer) {
    var page by rememberSaveable { mutableStateOf(AuthPage.LOGIN) }
    when (page) {
        AuthPage.LOGIN -> LoginScreen(container, onForgot = { page = AuthPage.FORGOT }, onJoin = { page = AuthPage.JOIN })
        AuthPage.JOIN -> JoinFlow(container, onBack = { page = AuthPage.LOGIN })
        AuthPage.FORGOT -> ForgotFlow(container, onBack = { page = AuthPage.LOGIN })
    }
}

/** Signed in but not ready: must set a password, or waiting for the admin's approval. */
@Composable
fun OnboardingScreen(container: AppContainer, state: AppContainer.Onboarding) {
    when (state) {
        is AppContainer.Onboarding.NeedsPassword -> AuthFrame("Set your password", state.email) {
            SetPasswordForm(container)
            TextButton(onClick = { container.cancelOnboarding() }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
        }
        is AppContainer.Onboarding.Pending -> PendingScreen(container, state.email)
    }
}

// ── Open an account ─────────────────────────────────────────────────────────────

@Composable
private fun JoinFlow(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }   // 0 details, 1 code, 2 password
    var code by rememberSaveable { mutableStateOf("") }
    var project by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler { if (step == 1) step = 0 else onBack() }

    LaunchedEffect(code) {
        project = null; checked = false
        if (code.trim().length < 6) return@LaunchedEffect
        delay(400)
        project = try { container.joinInfo(code) } catch (_: Exception) { null }
        checked = true
    }

    when (step) {
        0 -> AuthFrame("Open an account", project?.let { "Join $it" } ?: "Use the invite code from the admin") {
            Field(code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(12) }, "Invite code", mono = true, caps = true)
            if (checked && project == null) Note("This invite code is not valid. Ask the admin for the current one.", error = true)
            Field(name, { name = it }, "Full name", caps = false, words = true)
            Field(email, { email = it.trim() }, "Email", type = KeyboardType.Email)
            Field(note, { note = it }, "Team / position (optional)")
            error?.let { Note(it, error = true) }
            Button(onClick = {
                scope.launch {
                    busy = true; error = null
                    try { container.sendCode(email, createUser = true, fullName = name); step = 1 }
                    catch (e: Exception) { error = msg(e) }
                    busy = false
                }
            }, enabled = !busy && project != null && name.isNotBlank() && email.contains("@"), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(if (busy) "Sending code…" else "Email me a code")
            }
            Note("We'll email you a one-time code. Then you choose your own password. An admin approves new accounts.")
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("I already have an account") }
        }
        1 -> AuthFrame("Check your email", email) {
            CodeForm(container, email, createUser = true, onBack = { step = 0 }) {
                container.requestJoin(code, name, note, newUser = true)
                step = 2
            }
        }
        else -> AuthFrame("Set your password", email) {
            SetPasswordForm(container)   // afterwards: waiting screen until approved
        }
    }
}

// ── Forgot password ─────────────────────────────────────────────────────────────

@Composable
private fun ForgotFlow(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var email by rememberSaveable { mutableStateOf(container.session.email ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler { if (step == 1) step = 0 else onBack() }

    when (step) {
        0 -> AuthFrame("Forgot password", "We'll email you a one-time code") {
            Field(email, { email = it.trim() }, "Email", type = KeyboardType.Email)
            error?.let { Note(it, error = true) }
            Button(onClick = {
                scope.launch {
                    busy = true; error = null
                    try { container.sendCode(email, createUser = false); step = 1 } catch (e: Exception) { error = msg(e) }
                    busy = false
                }
            }, enabled = !busy && email.contains("@"), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(if (busy) "Sending…" else "Email me a code") }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("← Back to sign in") }
        }
        1 -> AuthFrame("Check your email", email) {
            CodeForm(container, email, createUser = false, onBack = { step = 0 }) { step = 2 }
        }
        else -> AuthFrame("Choose a new password", email) { SetPasswordForm(container) }
    }
}

// ── Waiting for approval ────────────────────────────────────────────────────────

@Composable
private fun PendingScreen(container: AppContainer, email: String) {
    val scope = rememberCoroutineScope()
    var requests by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tick) { requests = try { container.myJoinRequests() } catch (_: Exception) { emptyList() } }

    AuthFrame(if (requests.any { it.second == "pending" }) "Waiting for approval" else "No project access yet", email) {
        if (requests.isEmpty()) Note("Your account isn't part of a project yet. Enter the invite code from the admin, or ask them to add you.")
        requests.forEach { (p, st) ->
            Surface(color = if (st == "pending") Web.Amber50 else MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                Text("$p — " + if (st == "pending") "waiting for an admin to approve" else "not approved",
                    Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall,
                    color = if (st == "pending") Web.Amber700 else MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        error?.let { Note(it, error = true) }
        Button(onClick = {
            scope.launch {
                busy = true; error = null
                try { container.recheckAccess(); tick++ } catch (e: Exception) { error = msg(e) }
                busy = false
            }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(if (busy) "Checking…" else "Check again") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(12) }, label = { Text("Invite code") },
                singleLine = true, modifier = Modifier.weight(1f), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace))
            OutlinedButton(enabled = !busy && code.length >= 6, onClick = {
                scope.launch {
                    busy = true; error = null
                    try { container.requestJoin(code, "", null, newUser = false); code = ""; container.recheckAccess(); tick++ }
                    catch (e: Exception) { error = msg(e) }
                    busy = false
                }
            }) { Text("Request") }
        }
        TextButton(onClick = { container.cancelOnboarding() }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}

// ── Shared pieces ───────────────────────────────────────────────────────────────

@Composable
private fun CodeForm(container: AppContainer, email: String, createUser: Boolean, onBack: () -> Unit, onVerified: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var resent by remember { mutableStateOf(false) }
    Note("We emailed a code to $email. Enter it here (check Spam if you don't see it).")
    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(10) }, label = { Text("Code") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.fillMaxWidth())
    error?.let { Note(it, error = true) }
    if (resent) Note("New code sent.")
    Button(onClick = {
        scope.launch {
            busy = true; error = null
            try { container.verifyCode(email, code); onVerified() } catch (e: Exception) { error = msg(e) }
            busy = false
        }
    }, enabled = !busy && code.length >= 6, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(if (busy) "Checking…" else "Continue") }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onBack) { Text("← Change email") }
        TextButton(onClick = {
            scope.launch {
                error = null
                try { container.sendCode(email, createUser); resent = true } catch (e: Exception) { error = msg(e) }
            }
        }) { Text("Send a new code") }
    }
}

@Composable
private fun SetPasswordForm(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Note("Choose your own password (8+ characters). You'll use it to sign in on the phone and on the web.")
    val vt = if (show) VisualTransformation.None else PasswordVisualTransformation()
    OutlinedTextField(pw, { pw = it }, label = { Text("New password") }, singleLine = true, visualTransformation = vt,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(pw2, { pw2 = it }, label = { Text("Repeat password") }, singleLine = true, visualTransformation = vt,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = show, onCheckedChange = { show = it }); Text("Show password", style = MaterialTheme.typography.bodySmall)
    }
    error?.let { Note(it, error = true) }
    Button(onClick = {
        when {
            pw.length < 8 -> error = "Use at least 8 characters."
            pw != pw2 -> error = "The two passwords don't match."
            else -> scope.launch {
                busy = true; error = null
                try { container.setPassword(pw) } catch (e: Exception) { error = msg(e) }
                busy = false
            }
        }
    }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(if (busy) "Saving…" else "Save password") }
}

@Composable
private fun AuthFrame(title: String, sub: String?, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().statusBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Web.Teal700), contentAlignment = Alignment.Center) {
                    Text("TT", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = Web.Teal800)
                    sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            content()
        }
    }
}

@Composable
private fun Field(
    value: String, onChange: (String) -> Unit, label: String,
    type: KeyboardType = KeyboardType.Text, mono: Boolean = false, caps: Boolean = false, words: Boolean = false,
) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = type, capitalization = when {
            caps -> KeyboardCapitalization.Characters; words -> KeyboardCapitalization.Words; else -> KeyboardCapitalization.None }),
        textStyle = if (mono) LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace) else LocalTextStyle.current,
    )
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun msg(e: Exception) = when (e) {
    is IOException -> "No internet connection"
    else -> e.message ?: e.toString()
}
