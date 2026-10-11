package com.jarvis.ai.ui

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.jarvis.ai.R
import com.jarvis.ai.llm.ModelProviderCatalog
import com.jarvis.ai.llm.ModelProviderConfig
import com.jarvis.ai.llm.ProviderResolver
import com.jarvis.ai.llm.StoredProviderSettings
import com.jarvis.ai.network.CloudLLMClient
import com.jarvis.ai.network.CloudProviderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Renders the cloud provider list inside the dashboard: enable, edit, test, and reorder the AI
 * services Jarvis may call when the on-device model cannot answer.
 */
class ProviderPanel(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val container: LinearLayout,
    private val onStatus: (String) -> Unit,
) {
    private val store = CloudProviderStore(activity)

    /** Rebuilds the list; every provider is shown so setup stays discoverable. */
    fun render() {
        container.removeAllViews()
        val configs = store.configs()
        container.addView(header(configs))
        configs.forEachIndexed { index, config ->
            container.addView(row(config, isFirst = index == 0, isLast = index == configs.lastIndex))
        }
    }

    private fun header(configs: List<ModelProviderConfig>): View {
        val usable = configs.filter { it.isUsable }
        val summary = when {
            usable.isEmpty() -> "No cloud provider is ready yet. Edit one below to add its key."
            usable.size == 1 -> "Ready now: ${usable.first().summary()}"
            else -> "${usable.size} providers ready · first ${usable.first().summary()}"
        }
        return label(
            "Jarvis answers with the on-device model when a GGUF is installed, then calls these " +
                "providers in order.\n$summary",
            12.5f,
            R.color.jarvis_muted,
        ).apply { setPadding(dp(10), dp(2), dp(10), dp(8)) }
    }

    private fun row(config: ModelProviderConfig, isFirst: Boolean, isLast: Boolean): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }

        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(config.label, 14f, R.color.jarvis_text))
        val detail = config.blockedReason()?.let { "not used · $it" } ?: "ready · ${config.model}"
        texts.addView(label(detail, 11.5f, if (config.isUsable) R.color.jarvis_accent else R.color.jarvis_muted))
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(
            button("Edit") { showEditor(config) },
            LinearLayout.LayoutParams(dp(60), dp(36)).apply { marginEnd = dp(4) },
        )
        row.addView(
            button("▲") {
                store.move(config.id, -1)
                onStatus("Moved ${config.label} earlier in the cloud order.")
                render()
            }.apply {
                isEnabled = !isFirst
                alpha = if (isFirst) 0.4f else 1f
                contentDescription = "Move ${config.label} earlier"
            },
            LinearLayout.LayoutParams(dp(38), dp(36)).apply { marginEnd = dp(4) },
        )
        row.addView(
            button("▼") {
                store.move(config.id, 1)
                onStatus("Moved ${config.label} later in the cloud order.")
                render()
            }.apply {
                isEnabled = !isLast
                alpha = if (isLast) 0.4f else 1f
                contentDescription = "Move ${config.label} later"
            },
            LinearLayout.LayoutParams(dp(38), dp(36)),
        )
        return row
    }

    private fun showEditor(config: ModelProviderConfig) {
        val template = ModelProviderCatalog.template(config.id)
        val stored = store.stored(config.id)
        val buildKeyHint = if (store.hasBuildTimeKey(config.id)) "Blank keeps the key compiled into this build." else ""

        val enabledBox = CheckBox(activity).apply {
            text = "Enabled"
            isChecked = stored?.enabled ?: config.enabled
            setTextColor(activity.getColor(R.color.jarvis_text))
        }
        val defaultModel = template?.defaultModel.orEmpty()
        val defaultBaseUrl = template?.defaultBaseUrl.orEmpty()
        val modelField = field(
            hintText = if (defaultModel.isBlank()) "Model name" else "Model — blank uses $defaultModel",
            description = "Model name",
        ) { stored?.model.orEmpty() }
        val baseUrlField = field(
            hintText = defaultBaseUrl.ifBlank { "https://host/v1" },
            description = "Base URL",
        ) { stored?.baseUrl.orEmpty() }
        val keyField = field(
            hintText = buildKeyHint.ifBlank { "API key" },
            description = "API key",
        ) { stored?.apiKey.orEmpty() }.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val insecureBox = CheckBox(activity).apply {
            text = "Allow plain http:// endpoints (local servers only)"
            isChecked = stored?.allowInsecure ?: config.allowInsecure
            setTextColor(activity.getColor(R.color.jarvis_muted))
            textSize = 12f
        }

        var dialogRef: AlertDialog? = null
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
            addView(enabledBox)
            addView(modelField)
            addView(
                button("Fetch models from this provider") { fetchModels(config, modelField, baseUrlField, keyField, insecureBox) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)).apply { bottomMargin = dp(6) },
            )
            addView(baseUrlField)
            addView(keyField)
            addView(insecureBox)
            val notes = template?.notes.orEmpty()
            val docsUrl = template?.docsUrl.orEmpty()
            if (notes.isNotBlank()) {
                addView(label(notes, 11.5f, R.color.jarvis_muted))
            }
            if (docsUrl.isBlank()) {
                addView(
                    label(
                        "Self-hosted endpoints do not need a key unless the server requires one.",
                        11.5f,
                        R.color.jarvis_muted,
                    ),
                )
            } else {
                addView(
                    label("Get an API key →", 12f, R.color.jarvis_accent).apply {
                        setPadding(0, dp(6), 0, 0)
                        setOnClickListener {
                            runCatching {
                                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(docsUrl)))
                            }
                        }
                    },
                )
            }
            addView(
                button("Reset this provider") {
                    store.reset(config.id)
                    onStatus("Reset ${config.label} to its defaults.")
                    dialogRef?.dismiss()
                    render()
                }.apply { contentDescription = "Reset ${config.label}" },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)).apply {
                    topMargin = dp(6)
                },
            )
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(config.label)
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Save") { _: DialogInterface, _: Int ->
                save(config, enabledBox.isChecked, modelField, baseUrlField, keyField, insecureBox)
            }
            .setNeutralButton("Test", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialogRef = dialog

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                testConnection(config, modelField, baseUrlField, keyField, insecureBox)
            }
        }
        dialog.show()
    }

    private fun fetchModels(
        config: ModelProviderConfig,
        modelField: EditText,
        baseUrlField: EditText,
        keyField: EditText,
        insecureBox: CheckBox,
    ) {
        val draft = draftConfig(config, modelField, baseUrlField, keyField, insecureBox)
        scope.launch {
            onStatus("Fetching models from ${config.label}…")
            val result = withContext(Dispatchers.IO) {
                runCatching { CloudLLMClient(listOf(draft)).listModels(draft) }
            }
            result.onSuccess { models ->
                if (models.isEmpty()) {
                    Toast.makeText(activity, "No models were returned.", Toast.LENGTH_SHORT).show()
                    return@onSuccess
                }
                showModelPicker(config, models) { picked -> modelField.setText(picked) }
            }.onFailure { error ->
                val message = error.message?.take(180) ?: "Could not fetch models."
                onStatus("${config.label}: $message")
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showModelPicker(config: ModelProviderConfig, models: List<String>, onPicked: (String) -> Unit) {
        val options = models.take(MAX_MODEL_CHOICES).toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Models from ${config.label}")
            .setItems(options) { _: DialogInterface, which: Int ->
                if (which in options.indices) onPicked(options[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun testConnection(
        config: ModelProviderConfig,
        modelField: EditText,
        baseUrlField: EditText,
        keyField: EditText,
        insecureBox: CheckBox,
    ) {
        val draft = draftConfig(config, modelField, baseUrlField, keyField, insecureBox)
        scope.launch {
            onStatus("Testing ${config.label}…")
            val result = withContext(Dispatchers.IO) {
                runCatching { CloudLLMClient(listOf(draft)).verify(draft) }
            }
            val message = result.fold(
                onSuccess = { reply -> "${config.label} replied: ${reply.take(90)}" },
                onFailure = { error -> "${config.label} test failed: ${error.message?.take(160) ?: "request failed"}" },
            )
            onStatus(message)
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun save(
        config: ModelProviderConfig,
        enabled: Boolean,
        modelField: EditText,
        baseUrlField: EditText,
        keyField: EditText,
        insecureBox: CheckBox,
    ) {
        val settings = StoredProviderSettings(
            enabled = enabled,
            model = modelField.text.toString().trim(),
            baseUrl = baseUrlField.text.toString().trim(),
            apiKey = keyField.text.toString().trim(),
            allowInsecure = insecureBox.isChecked,
        )
        store.save(config.id, settings)
        val updated = store.configs().firstOrNull { it.id == config.id }
        val warning = when {
            updated == null -> null
            !enabled -> null
            else -> updated.blockedReason()?.let { "${config.label} is enabled but $it." }
        }
        onStatus(warning ?: "Saved ${config.label}.")
        if (warning != null) Toast.makeText(activity, warning, Toast.LENGTH_LONG).show()
        render()
    }

    private fun draftConfig(
        config: ModelProviderConfig,
        modelField: EditText,
        baseUrlField: EditText,
        keyField: EditText,
        insecureBox: CheckBox,
    ): ModelProviderConfig = config.copy(
        model = modelField.text.toString().trim().ifBlank { config.model },
        baseUrl = ProviderResolver.normalizeBaseUrl(
            baseUrlField.text.toString().trim().ifBlank { config.baseUrl },
        ),
        apiKey = keyField.text.toString().trim().ifBlank { config.apiKey },
        enabled = true,
        allowInsecure = insecureBox.isChecked,
    )

    /** Creates a text field whose hint doubles as documentation of the provider default. */
    private fun field(hintText: String, description: String, initial: () -> String): EditText =
        EditText(activity).apply {
            hint = hintText
            setText(initial())
            textSize = 14f
            setTextColor(activity.getColor(R.color.jarvis_text))
            setHintTextColor(activity.getColor(R.color.jarvis_muted))
            contentDescription = description
            inputType = InputType.TYPE_CLASS_TEXT
        }

    private fun label(textValue: String, sizeSp: Float, colorRes: Int): TextView = TextView(activity).apply {
        text = textValue
        textSize = sizeSp
        setTextColor(activity.getColor(colorRes))
        includeFontPadding = true
    }

    private fun button(title: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(activity).apply {
            this.text = title
            isAllCaps = false
            textSize = 12f
            setTextColor(activity.getColor(R.color.jarvis_accent))
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                activity.getColor(R.color.jarvis_surface),
            )
            strokeColor = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.jarvis_accent))
            strokeWidth = dp(1)
            cornerRadius = dp(10)
            minHeight = dp(36)
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_MODEL_CHOICES = 300
    }
}
