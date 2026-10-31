package com.shwai.sdk.orchestration

import com.shwai.sdk.intelligence.FunctionDeclaration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** READ_ONLY runs unattended. DRAFT and EXTERNAL_SEND always require IME-window approval. */
enum class RiskLevel { READ_ONLY, DRAFT, EXTERNAL_SEND }

data class ProposalField(val label: String, val value: String)

data class ActionProposal(
    val toolName: String,
    val title: String,
    val risk: RiskLevel,
    val fields: List<ProposalField>,
    val args: JsonObject,
)

sealed interface ToolOutcome {
    /** [insertText] is offered to the user via the IME "Replace" button; it is never sent to the model. */
    data class Success(
        val summary: String,
        val data: JsonObject = JsonObject(emptyMap()),
        val insertText: String? = null,
    ) : ToolOutcome

    /** [needsCompanion]: requires a permission/OAuth step only the companion app can perform. */
    data class Failure(val message: String, val needsCompanion: Boolean = false) : ToolOutcome
}

interface AgentTool {
    val name: String
    val description: String
    val parameters: JsonObject
    val risk: RiskLevel

    /** Side-effect free. Builds the confirmation card shown before [execute]. May throw IllegalArgumentException. */
    suspend fun propose(args: JsonObject): ActionProposal

    suspend fun execute(args: JsonObject): ToolOutcome
}

internal fun AgentTool.declaration() = FunctionDeclaration(name, description, parameters)

internal fun ToolOutcome.toJson(): JsonObject = when (this) {
    is ToolOutcome.Success -> buildJsonObject {
        put("ok", true)
        put("summary", summary)
        put("data", data)
    }
    is ToolOutcome.Failure -> buildJsonObject {
        put("ok", false)
        put("error", message)
    }
}
