package com.example.ui.panels

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CardTop = Color(0xFF0B2A1F)
private val CardBottom = Color(0xFF071A2E)
private val Accent = Color(0xFF00E676)
private val FieldBg = Color(0xFF05101C)
private val FieldBorder = Color(0xFF1B3A52)
private val Muted = Color(0xFF8FA3B8)
private val Warn = Color(0xFFFFB300)

/**
 * Login details of a freshly installed panel (BPB or 3X-UI): private link, username and password
 * in separate, copyable rows plus a one-tap "copy everything".
 *
 * [healthNote] is the outcome of the post-install checks; it is shown as a status line so the user
 * can see whether the link and login were actually verified.
 */
@Composable
internal fun PanelCredentialsCard(
    panelName: String,
    link: String,
    username: String,
    password: String,
    healthNote: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var passwordVisible by remember { mutableStateOf(false) }

    fun copy(label: String, value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    }

    val verified = healthNote.contains("verified", ignoreCase = true) &&
        !healthNote.contains("not ", ignoreCase = true)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Accent.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .background(Brush.verticalGradient(listOf(CardTop, CardBottom)))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(Accent.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Panel access",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = panelName,
                        color = Muted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            CredentialRow(
                label = "LOGIN LINK",
                value = link,
                icon = Icons.Default.Language,
                maxLines = 2,
                onCopy = { copy("Login link", link) }
            )
            CredentialRow(
                label = "USERNAME",
                value = username,
                icon = Icons.Default.Person,
                onCopy = { copy("Username", username) }
            )
            CredentialRow(
                label = "PASSWORD",
                value = if (passwordVisible) password else "•".repeat(password.length.coerceIn(8, 20)),
                icon = Icons.Default.Lock,
                valueColor = Accent,
                onCopy = { copy("Password", password) },
                trailing = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                            tint = Muted,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            )

            if (healthNote.isNotBlank()) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = if (verified) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (verified) Accent else Warn,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = healthNote,
                        color = if (verified) Accent else Warn,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            OutlinedButton(
                onClick = {
                    copy(
                        "Panel access",
                        "Panel: $panelName\nLink: $link\nUsername: $username\nPassword: $password"
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, FieldBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy link, username & password", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun CredentialRow(
    label: String,
    value: String,
    icon: ImageVector,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.White,
    maxLines: Int = 1,
    trailing: @Composable (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = FieldBg,
        border = BorderStroke(1.dp, FieldBorder)
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = Muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = Muted,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = value,
                    color = valueColor,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis
                )
            }
            trailing?.invoke()
            IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy $label",
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}
