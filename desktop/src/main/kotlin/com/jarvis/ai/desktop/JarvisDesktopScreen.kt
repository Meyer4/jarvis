package com.jarvis.ai.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jarvis.ai.llm.BuildTimeKeys
import com.jarvis.ai.llm.JarvisPrompts
import com.jarvis.ai.llm.ModelProviderCatalog
import com.jarvis.ai.llm.ModelProviderConfig
import com.jarvis.ai.llm.ProviderRepository
import com.jarvis.ai.llm.ProviderResolver
import com.jarvis.ai.llm.StoredProviderSettings
import com.jarvis.ai.network.ChatMessage
import com.jarvis.ai.network.CloudLLMClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One line of the on-screen conversation. */
private data class ChatTurn(
    val role: String,
    val text: String,
    val source: String? = null,
)

@Composable
fun JarvisDesktopScreen() {
    val repository = remember { createRepository() }
    var configs by remember { mutableStateOf(repository.configs()) }
    val client = remember { CloudLLMClient { repository.usableConfigs() } }
    val turns = remember { mutableStateListOf<ChatTurn>() }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(readySummary(repository.configs())) }

    fun refresh(message: String? = null) {
        configs = repository.configs()
        status = message ?: readySummary(configs)
    }

    fun send(text: String) {
        val question = text.trim()
        if (question.isEmpty()) return
        turns += ChatTurn("user", question)
        status = "Thinking…"
        scope.launch {
            val payload = buildList {
                add(ChatMessage("system", JarvisPrompts.system("desktop")))
                turns.forEach { turn -> add(ChatMessage(turn.role, turn.text)) }
            }
            val result = withContext(Dispatchers.IO) { runCatching { client.complete(payload) } }
            result
                .onSuccess { answer ->
                    turns += ChatTurn("assistant", answer.text, answer.provider.summary())
                    status = "Answered by ${answer.provider.summary()}"
                }
                .onFailure { error ->
                    status = error.message?.take(240) ?: "The request failed."
                }
        }
    }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ProviderColumn(
            modifier = Modifier.width(380.dp).fillMaxSize(),
            configs = configs,
            repository = repository,
            onChanged = { message -> refresh(message) },
        )
        Box(
            Modifier
                .width(1.dp)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        ChatColumn(
            modifier = Modifier.weight(1f).fillMaxSize(),
            turns = turns,
            status = status,
            onSend = { text -> send(text) },
            onClear = {
                turns.clear()
                status = readySummary(configs)
            },
        )
    }
}

/** Builds the repository, reading build-time keys straight from the environment. */
private fun createRepository(): ProviderRepository {
    val environmentKeys = ModelProviderCatalog.templates.associate { template ->
        template.keyEnvironment to (System.getenv(template.keyEnvironment) ?: "")
    }
    return ProviderRepository(
        storage = DesktopSettingsStorage(),
        buildTimeKeys = BuildTimeKeys.parse(
            ModelProviderCatalog.templates.joinToString(";") { template ->
                "${template.keyEnvironment}=${environmentKeys[template.keyEnvironment].orEmpty()}"
            },
        ),
    )
}

private fun readySummary(configs: List<ModelProviderConfig>): String {
    val usable = configs.filter { it.isUsable }
    return when {
        usable.isEmpty() -> "No provider is ready. Enable one on the left and add its API key."
        usable.size == 1 -> "Ready: ${usable.first().summary()}"
        else -> "${usable.size} providers ready · first ${usable.first().summary()}"
    }
}

@Composable
private fun ProviderColumn(
    modifier: Modifier,
    configs: List<ModelProviderConfig>,
    repository: ProviderRepository,
    onChanged: (String?) -> Unit,
) {
    Column(modifier.padding(16.dp)) {
        Text(
            "JARVIS · AI PROVIDERS",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Enable the models Jarvis may use. The first ready provider answers; the rest are fallbacks.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(configs, key = { it.id }) { config ->
                ProviderCard(config, repository, onChanged)
            }
        }
    }
}

@Composable
private fun ProviderCard(
    config: ModelProviderConfig,
    repository: ProviderRepository,
    onChanged: (String?) -> Unit,
) {
    var enabled by remember(config.id) { mutableStateOf(config.enabled) }
    var model by remember(config.id) { mutableStateOf(config.model) }
    var baseUrl by remember(config.id) { mutableStateOf(config.baseUrl) }
    var apiKey by remember(config.id) { mutableStateOf(config.apiKey) }
    var allowInsecure by remember(config.id) { mutableStateOf(config.allowInsecure) }
    var fetched by remember(config.id) { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember(config.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun draft() = config.copy(
        model = model.trim().ifBlank { config.model },
        baseUrl = ProviderResolver.normalizeBaseUrl(baseUrl.trim().ifBlank { config.baseUrl }),
        apiKey = apiKey.trim().ifBlank { config.apiKey },
        enabled = true,
        allowInsecure = allowInsecure,
    )

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    config.label,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { value ->
                        enabled = value
                        repository.save(
                            config.id,
                            StoredProviderSettings(
                                enabled = value,
                                model = model.trim(),
                                baseUrl = baseUrl.trim(),
                                apiKey = apiKey.trim(),
                                allowInsecure = allowInsecure,
                            ),
                        )
                        onChanged(if (value) "${config.label} enabled." else "${config.label} disabled.")
                    },
                )
            }
            val blocked = config.blockedReason()
            Text(
                if (blocked == null) "ready · ${config.model}" else "not used · $blocked",
                style = MaterialTheme.typography.bodySmall,
                color = if (blocked == null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(if (config.keyRequired) "API key" else "API key (optional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (config.usesPlainHttp || allowInsecure) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowInsecure, onCheckedChange = { allowInsecure = it })
                    Text(
                        "Allow plain http:// (local servers only)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        repository.save(
                            config.id,
                            StoredProviderSettings(
                                enabled = enabled,
                                model = model.trim(),
                                baseUrl = baseUrl.trim(),
                                apiKey = apiKey.trim(),
                                allowInsecure = allowInsecure,
                            ),
                        )
                        onChanged("Saved ${config.label}.")
                    },
                ) { Text("Save") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        onChanged("Testing ${config.label}…")
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { CloudLLMClient(listOf(draft())).verify(draft()) }
                            }
                            onChanged(
                                result.fold(
                                    onSuccess = { reply -> "${config.label} replied: ${reply.take(80)}" },
                                    onFailure = { error ->
                                        "${config.label} test failed: ${error.message?.take(150) ?: "error"}"
                                    },
                                ),
                            )
                            busy = false
                        }
                    },
                ) { Text(if (busy) "Working…" else "Test") }
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        onChanged("Fetching models from ${config.label}…")
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { CloudLLMClient(listOf(draft())).listModels(draft()) }
                            }
                            result
                                .onSuccess { models ->
                                    fetched = models
                                    onChanged("${config.label} offers ${models.size} models — pick one below.")
                                }
                                .onFailure { error ->
                                    onChanged("${config.label}: ${error.message?.take(150) ?: "could not list models"}")
                                }
                            busy = false
                        }
                    },
                ) { Text("Fetch models") }
                TextButton(
                    onClick = {
                        repository.reset(config.id)
                        fetched = emptyList()
                        onChanged("Reset ${config.label} to its defaults.")
                    },
                ) { Text("Reset") }
            }
            if (fetched.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    fetched.take(40).forEach { candidate ->
                        TextButton(onClick = { model = candidate }) { Text(candidate) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatColumn(
    modifier: Modifier,
    turns: List<ChatTurn>,
    status: String,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(turns.size) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
    }

    Column(modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "JARVIS",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Ask anything. Answers come from the first ready provider on the left.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClear) { Text("Clear chat") }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(turns) { turn -> TurnRow(turn) }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Ask Jarvis…") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = input.isNotBlank(),
                onClick = {
                    onSend(input)
                    input = ""
                },
            ) { Text("Send") }
        }
    }
}

@Composable
private fun TurnRow(turn: ChatTurn) {
    val isUser = turn.role == "user"
    Surface(
        color = if (isUser) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (isUser) "You" else "Jarvis · ${turn.source ?: "provider"}",
                style = MaterialTheme.typography.labelSmall,
                color = if (isUser) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Text(
                turn.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isUser) Color.Unspecified else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
