package com.elfred434.transciber.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.elfred434.transciber.domain.HistoryItem
import com.elfred434.transciber.domain.MainUiState
import com.elfred434.transciber.domain.ProcessingStatus
import java.text.DateFormat
import java.util.Date

private enum class AppTab { HOME, HISTORY, SETTINGS }

@Composable
fun TransciberApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    var currentTab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        viewModel.acceptAudio(uri)
    }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.transcribe()
        else viewModel.showError("L'autorisation audio est nécessaire pour la reconnaissance hors ligne.")
    }
    val startTranscription = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            viewModel.transcribe()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding(),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    selected = currentTab == AppTab.HOME,
                    onClick = { currentTab = AppTab.HOME },
                    icon = { Icon(Icons.Default.Home, null) },
                    label = { Text("Accueil") }
                )
                NavigationBarItem(
                    selected = currentTab == AppTab.HISTORY,
                    onClick = { currentTab = AppTab.HISTORY },
                    icon = { Icon(Icons.Default.CheckCircle, null) },
                    label = { Text("Historique") }
                )
                NavigationBarItem(
                    selected = currentTab == AppTab.SETTINGS,
                    onClick = { currentTab = AppTab.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("Réglages") }
                )
            }
        }
    ) { padding ->
        when (currentTab) {
            AppTab.HOME -> HomeScreen(
                state = state,
                viewModel = viewModel,
                onPickAudio = { picker.launch(arrayOf("audio/*")) },
                onTranscribe = startTranscription,
                modifier = Modifier.padding(padding)
            )
            AppTab.HISTORY -> HistoryScreen(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.padding(padding)
            )
            AppTab.SETTINGS -> SettingsScreen(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun HomeScreen(
    state: MainUiState,
    viewModel: MainViewModel,
    onPickAudio: () -> Unit,
    onTranscribe: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Transciber", fontSize = 28.sp, fontWeight = FontWeight.Black)
                    Text("Les vocaux, enfin lisibles.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AssistChip(
                    onClick = { },
                    label = { Text("WhatsApp") },
                    leadingIcon = { Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)) }
                )
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = Color(0xFF112822)
            ) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Transformez un vocal en texte.", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Le vocal est transcrit sur cet appareil, même hors ligne. Seule la traduction ou le résumé utilisent votre gateway Gemini.",
                        color = Color(0xFFC6DED0), lineHeight = 21.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(shape = RoundedCornerShape(50), color = Color(0xFFB9F2D5)) {
                            Text("Hors ligne", Modifier.padding(horizontal = 13.dp, vertical = 7.dp), color = Color(0xFF112822), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Surface(shape = RoundedCornerShape(50), color = Color(0xFF2A4439)) {
                            Text("Privé par défaut", Modifier.padding(horizontal = 13.dp, vertical = 7.dp), color = Color(0xFFC6DED0), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        item {
            AudioInputCard(
                state = state,
                onPickAudio = onPickAudio,
                onTogglePlayback = viewModel::togglePlayback,
                onSetPlaybackSpeed = viewModel::setPlaybackSpeed
            )
        }
        item {
            if (state.status == ProcessingStatus.TRANSCRIBING ||
                state.status == ProcessingStatus.TRANSLATING ||
                state.status == ProcessingStatus.SUMMARIZING
            ) {
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)))
                Spacer(Modifier.height(5.dp))
            }
            state.errorMessage?.let { message ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(message, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        if (state.transcript.isNotBlank()) {
            item {
                ResultCard(
                    title = "Transcription",
                    icon = { Icon(Icons.Default.PlayArrow, null) },
                    text = state.transcript,
                    onCopy = { clipboard.setText(AnnotatedString(state.transcript)) },
                    onShare = { viewModel.shareText(state.transcript) }
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = viewModel::translate,
                        modifier = Modifier.weight(1f),
                        enabled = state.status != ProcessingStatus.TRANSLATING
                    ) {
                        Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Traduire")
                    }
                    OutlinedButton(
                        onClick = viewModel::summarize,
                        modifier = Modifier.weight(1f),
                        enabled = state.status != ProcessingStatus.SUMMARIZING
                    ) {
                        Icon(Icons.Default.Settings, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Résumer")
                    }
                }
            }
        } else if (state.audioUri != null) {
            item {
                Button(
                    onClick = onTranscribe,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.status != ProcessingStatus.TRANSCRIBING
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Transcrire le vocal")
                }
            }
        }
        if (state.translation.isNotBlank()) {
            item {
                ResultCard(
                    title = "Traduction · ${state.settings.targetLanguage}",
                    icon = { Icon(Icons.Default.Settings, null) },
                    text = state.translation,
                    tint = Color(0xFFE9F5EC),
                    onCopy = { clipboard.setText(AnnotatedString(state.translation)) },
                    onShare = { viewModel.shareText(state.translation) }
                )
            }
        }
        if (state.summary.isNotBlank()) {
            item {
                ResultCard(
                    title = "Résumé Gemini",
                    icon = { Icon(Icons.Default.Settings, null) },
                    text = state.summary,
                    tint = Color(0xFFFFF0E9),
                    onCopy = { clipboard.setText(AnnotatedString(state.summary)) },
                    onShare = { viewModel.shareText(state.summary) }
                )
            }
        }
        if (state.transcript.isNotBlank()) {
            item {
                OutlinedButton(
                    onClick = viewModel::saveCurrent,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.settings.incognitoMode
                ) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            state.settings.incognitoMode -> "Mode incognito actif"
                            state.settings.autoSaveHistory -> "Enregistré dans l’historique"
                            else -> "Enregistrer dans l’historique"
                        }
                    )
                }
            }
        }
        item { Text("Conseil : pour utiliser WhatsApp, appuyez longuement sur le vocal puis choisissez Partager → Transciber.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
    }
}

@Composable
private fun AudioInputCard(
    state: MainUiState,
    onPickAudio: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit
) {
    val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.padding(11.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text("Votre message vocal", fontWeight = FontWeight.Bold)
                    Text(
                        state.audioName ?: "Aucun fichier sélectionné",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusPill(state.status)
            }
            if (state.audioUri != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onTogglePlayback, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(if (state.isPlaying) "Pause" else "Lire")
                    }
                    Text("${state.playbackSpeed}×", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    speeds.forEach { speed ->
                        FilterChip(
                            selected = state.playbackSpeed == speed,
                            onClick = { onSetPlaybackSpeed(speed) },
                            label = { Text("${speed}×") }
                        )
                    }
                }
            }
            OutlinedButton(onClick = onPickAudio, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Choisir un fichier audio")
            }
        }
    }
}

@Composable
private fun StatusPill(status: ProcessingStatus) {
    val (label, color) = when (status) {
        ProcessingStatus.READY -> "Prêt" to Color(0xFFB9F2D5)
        ProcessingStatus.TRANSCRIBING -> "Transcription" to Color(0xFFFFE7B0)
        ProcessingStatus.TRANSLATING -> "Traduction" to Color(0xFFDDE9FF)
        ProcessingStatus.SUMMARIZING -> "Résumé" to Color(0xFFFFDCD0)
        ProcessingStatus.SUCCESS -> "Terminé" to Color(0xFFB9F2D5)
        ProcessingStatus.ERROR -> "Erreur" to Color(0xFFFFD5D2)
        ProcessingStatus.IDLE -> "En attente" to Color(0xFFE6EFE9)
    }
    Surface(shape = RoundedCornerShape(50), color = color) {
        Text(label, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF213D31))
    }
}

@Composable
private fun ResultCard(
    title: String,
    icon: @Composable () -> Unit,
    text: String,
    tint: Color = MaterialTheme.colorScheme.surface,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = tint)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(9.dp))
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onCopy) { Text("Copier") }
                TextButton(onClick = onShare) { Text("Partager") }
            }
            HorizontalDivider(color = Color.Black.copy(alpha = 0.08f))
            Text(text, lineHeight = 22.sp)
        }
    }
}

@Composable
private fun HistoryScreen(state: MainUiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Historique", fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text("Vos transcriptions restent sur cet appareil.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.history.isNotEmpty()) {
                TextButton(onClick = viewModel::clearHistory) { Text("Effacer") }
            }
        }
        Spacer(Modifier.height(18.dp))
        if (state.history.isEmpty()) {
            EmptyHistory()
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.history, key = { it.id }) { item -> HistoryRow(item) }
            }
        }
    }
}

@Composable
private fun EmptyHistory() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Icon(Icons.Default.Settings, null, Modifier.padding(18.dp).size(36.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text("Votre historique est vide", fontWeight = FontWeight.Bold)
            Text("Les transcriptions sauvegardées apparaîtront ici.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.audioName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.createdAt)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(item.transcript, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
            if (item.translation.isNotBlank()) {
                Text(item.translation, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: MainUiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    var gatewayUrl by remember(state.settings.gatewayUrl) { mutableStateOf(state.settings.gatewayUrl) }
    var gatewayToken by remember(state.settings.gatewayToken) { mutableStateOf(state.settings.gatewayToken) }
    val languages = listOf("Français", "English", "Español", "Português", "Italiano", "Deutsch")
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Réglages", fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text("Configurez le gateway qui protège votre clé Gemini.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionTitle("Connexion Gemini")
        OutlinedTextField(
            value = gatewayUrl,
            onValueChange = { gatewayUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("URL du gateway") },
            placeholder = { Text("https://api.exemple.com/") },
            leadingIcon = { Icon(Icons.Default.Share, null) },
            singleLine = true
        )
        OutlinedTextField(
            value = gatewayToken,
            onValueChange = { gatewayToken = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Jeton client (optionnel)") },
            leadingIcon = { Icon(Icons.Default.Settings, null) },
            singleLine = true
        )
        Button(
            onClick = { viewModel.setGatewayUrl(gatewayUrl); viewModel.setGatewayToken(gatewayToken) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Enregistrer la connexion") }
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                "La clé GEMINI_API_KEY ne doit jamais être placée dans l’application Android. Elle reste côté serveur.",
                Modifier.padding(14.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SectionTitle("Langue cible")
        Text("Traduire vers", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            languages.take(3).forEach { language ->
                FilterChip(
                    selected = state.settings.targetLanguage == language,
                    onClick = { viewModel.setTargetLanguage(language) },
                    label = { Text(language) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            languages.drop(3).forEach { language ->
                FilterChip(
                    selected = state.settings.targetLanguage == language,
                    onClick = { viewModel.setTargetLanguage(language) },
                    label = { Text(language) }
                )
            }
        }
        SectionTitle("Comportement")
        SettingSwitch("Sauvegarder automatiquement l’historique", state.settings.autoSaveHistory, viewModel::setAutoSave)
        SettingSwitch("Mode incognito", state.settings.incognitoMode, viewModel::setIncognitoMode)
        if (state.settings.incognitoMode) {
            Text("Les nouveaux résultats ne seront pas ajoutés à l’historique local.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        SettingSwitch("Traitement en arrière-plan", state.settings.backgroundMode, viewModel::setBackgroundMode)
        SettingSwitch("Capteur de proximité pour la lecture", state.settings.proximitySensor, viewModel::setProximity)
        SectionTitle("Lecture")
        SettingLine(Icons.Default.Settings, "Vitesse minimale", "0,75×")
        SettingLine(Icons.Default.Settings, "Vitesse maximale", "1,75×")
        SettingLine(Icons.Default.Share, "Sortie audio", "Haut-parleur / écouteur")
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingLine(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(label, Modifier.weight(1f))
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
