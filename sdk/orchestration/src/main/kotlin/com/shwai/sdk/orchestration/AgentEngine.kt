package com.shwai.sdk.orchestration

import com.shwai.core.database.AuditLogDao
import com.shwai.core.database.AuditLogEntity
import com.shwai.sdk.intelligence.FunctionCall
import com.shwai.sdk.intelligence.FunctionResponse
import com.shwai.sdk.intelligence.GenerationRequest
import com.shwai.sdk.intelligence.GenerationResult
import com.shwai.sdk.intelligence.IntelligenceClient
import com.shwai.sdk.intelligence.Turn
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

sealed interface AgentState {
    data object Idle : AgentState
    data class Working(val label: String) : AgentState
    data class AwaitingConfirmation(val proposal: ActionProposal) : AgentState
    /** [insertText]: text the user may put into the field (generated text, or summaries from tools). */
    data class Completed(val text: String, val insertText: String?) : AgentState
    data class Failed(val message: String) : AgentState
}

class AgentEngine(
    private val intelligence: IntelligenceClient,
    tools: List<AgentTool>,
    private val audit: AuditLogDao,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val registry = tools.associateBy { it.name }
    private val declarations = tools.map { it.declaration() }
    private val history = mutableListOf<Turn>()
    private val insertParts = mutableListOf<String>()
    private var job: Job? = null
    private var pending: FunctionCall? = null
    private var toolsUsed = 0

    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    fun submit(instruction: String) {
        reset()
        history += Turn.User("Now: ${nowLine()}\nRequest: $instruction")
        launchGuarded { loop() }
    }

    fun confirm() {
        val call = pending ?: return
        val tool = registry[call.name] ?: return
        val title = (state.value as? AgentState.AwaitingConfirmation)?.proposal?.title ?: tool.name
        pending = null
        launchGuarded {
            _state.value = AgentState.Working("Running: $title")
            history += Turn.ToolResult(FunctionResponse(call.name, runTool(tool, call.args, title)))
            loop()
        }
    }

    fun reject() {
        val call = pending ?: return
        val tool = registry[call.name]
        pending = null
        scope.launch {
            audit.insert(
                AuditLogEntity(
                    timestamp = now(), toolName = call.name,
                    risk = tool?.risk?.name.orEmpty(), summary = call.name, outcome = "REJECTED",
                )
            )
        }
        reset()
    }

    /** Cancels any run and drops pending approvals. Called when the IME window closes. */
    fun dismiss() = reset()

    fun fail(message: String) {
        reset()
        _state.value = AgentState.Failed(message)
    }

    private fun reset() {
        job?.cancel()
        job = null
        pending = null
        toolsUsed = 0
        history.clear()
        insertParts.clear()
        _state.value = AgentState.Idle
    }

    private fun nowLine(): String {
        val z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now()), ZoneId.systemDefault())
        return "${z.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)} (${z.dayOfWeek})"
    }

    private fun launchGuarded(block: suspend () -> Unit) {
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = AgentState.Failed(e.message ?: "Unexpected error")
            }
        }
    }

    private suspend fun loop() {
        for (step in 0 until MAX_STEPS) {
            _state.value = AgentState.Working("Thinking…")
            when (val result = intelligence.generate(GenerationRequest(SYSTEM_PROMPT, history.toList(), declarations))) {
                is GenerationResult.Failure -> {
                    _state.value = AgentState.Failed(result.message)
                    return
                }
                is GenerationResult.Text -> {
                    history += Turn.ModelText(result.text)
                    val insert = if (toolsUsed == 0) result.text else insertParts.joinToString("\n").ifBlank { null }
                    _state.value = AgentState.Completed(result.text, insert)
                    return
                }
                is GenerationResult.Call -> {
                    val call = result.call
                    history += Turn.ModelCall(call)
                    val tool = registry[call.name]
                    if (tool == null) {
                        history += Turn.ToolResult(
                            FunctionResponse(call.name, ToolOutcome.Failure("Unknown tool").toJson())
                        )
                        continue
                    }
                    if (tool.risk == RiskLevel.READ_ONLY) {
                        _state.value = AgentState.Working("Running: ${tool.name}")
                        history += Turn.ToolResult(FunctionResponse(call.name, runTool(tool, call.args, tool.name)))
                    } else {
                        val proposal = try {
                            tool.propose(call.args)
                        } catch (e: IllegalArgumentException) {
                            history += Turn.ToolResult(
                                FunctionResponse(call.name, ToolOutcome.Failure(e.message ?: "Invalid arguments").toJson())
                            )
                            continue
                        }
                        pending = call
                        _state.value = AgentState.AwaitingConfirmation(proposal)
                        return
                    }
                }
            }
        }
        _state.value = AgentState.Failed("Step limit reached")
    }

    private suspend fun runTool(tool: AgentTool, args: JsonObject, title: String): JsonObject {
        toolsUsed++
        val outcome = try {
            tool.execute(args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutcome.Failure(e.message ?: "Tool error")
        }
        if (outcome is ToolOutcome.Success) outcome.insertText?.let { insertParts += it }
        audit.insert(
            AuditLogEntity(
                timestamp = now(), toolName = tool.name, risk = tool.risk.name, summary = title,
                outcome = if (outcome is ToolOutcome.Success) "OK" else "FAILED",
            )
        )
        return outcome.toJson()
    }

    private companion object {
        const val MAX_STEPS = 6
        const val SYSTEM_PROMPT =
            "You are ShwAI, an assistant inside an Android keyboard. The first line of the user's message is the " +
                "current date and time; the text after 'Request:' is what they typed. Tools: find_files (on-device " +
                "images, video, audio), create_calendar_event (opens the Calendar app prefilled; the user taps Save), " +
                "create_map_link (Google Maps link for a place), create_gmail_draft, share_files. " +
                "If the request describes a plan or event with a day or time (including Hinglish or Telglish such as " +
                "'kal' or 'repu'), call create_calendar_event with an absolute ISO-8601 local start computed from the " +
                "current date, and if it names a place also call create_map_link for that place. If the time is " +
                "missing, ask one short question instead of guessing. Never claim an action succeeded unless its tool " +
                "result has ok=true; for the calendar, say it was opened for the user to save. The Gmail tool only " +
                "creates drafts. File URIs must come from find_files. If the user wants text written or rewritten, " +
                "reply with only that text and no preamble. After tools finish, reply with one short sentence."
    }
}
