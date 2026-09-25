package com.jarvis.ai.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Routes explicit voice commands to a small allowlist of native Android system actions. */
class ToolRegistry(context: Context) {
    private val tools = SystemTools(context.applicationContext)

    /** Returns null for ordinary assistant questions and a result for supported device commands. */
    suspend fun execute(command: String): ToolResult? = withContext(Dispatchers.IO) {
        tools.execute(command)
    }
}

/** The user-facing result of one confirmed, allowlisted system action. */
data class ToolResult(
    val spokenMessage: String,
    val actionName: String,
)
