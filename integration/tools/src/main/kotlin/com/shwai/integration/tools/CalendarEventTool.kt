package com.shwai.integration.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.shwai.sdk.orchestration.ActionProposal
import com.shwai.sdk.orchestration.AgentTool
import com.shwai.sdk.orchestration.ProposalField
import com.shwai.sdk.orchestration.RiskLevel
import com.shwai.sdk.orchestration.ToolOutcome
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Opens the system Calendar with a prefilled event. The user taps Save there; nothing is written silently. */
class CalendarEventTool(
    private val context: Context,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Instant = { Instant.now() },
) : AgentTool {
    override val name = "create_calendar_event"
    override val description =
        "Add an event to the user's calendar. Opens the Calendar app with the event filled in; the user taps Save there."
    override val risk = RiskLevel.DRAFT
    override val parameters = objectSchema(
        listOf("title", "start"),
        "title" to str("Short event title"),
        "start" to str("Start as ISO-8601 local date-time, e.g. 2026-11-01T17:00:00, computed from the current date"),
        "duration_minutes" to integer("Length in minutes (default 60)"),
        "location" to str("Place name or address"),
        "notes" to str("Optional notes"),
    )

    private class Event(
        val title: String,
        val start: ZonedDateTime,
        val minutes: Int,
        val location: String?,
        val notes: String?,
    )

    private fun parse(args: JsonObject): Event {
        val title = args.string("title")?.trim().orEmpty()
        require(title.isNotEmpty()) { "Event title is missing." }
        val raw = args.string("start")?.trim().orEmpty()
        val z = zone()
        val start = try {
            OffsetDateTime.parse(raw).atZoneSameInstant(z)
        } catch (e: DateTimeException) {
            try {
                LocalDateTime.parse(raw).atZone(z)
            } catch (e2: DateTimeException) {
                throw IllegalArgumentException("Start must be ISO-8601 like 2026-11-01T17:00:00.")
            }
        }
        val now = clock().atZone(z)
        require(start.isAfter(now.minusDays(1)) && start.isBefore(now.plusYears(2))) {
            "Start time is outside the allowed range."
        }
        return Event(
            title = title.take(120),
            start = start,
            minutes = (args.int("duration_minutes") ?: 60).coerceIn(5, 1440),
            location = args.string("location")?.trim()?.take(200)?.ifBlank { null },
            notes = args.string("notes")?.trim()?.take(500)?.ifBlank { null },
        )
    }

    private fun whenText(e: Event): String =
        e.start.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy, h:mm a", Locale.ENGLISH))

    private fun summary(e: Event): String = buildString {
        append("\uD83D\uDCC5 ").append(e.title).append(" - ").append(whenText(e))
        e.location?.let { append(" @ ").append(it) }
    }

    override suspend fun propose(args: JsonObject): ActionProposal {
        val e = parse(args)
        return ActionProposal(
            toolName = name,
            title = "Add to calendar",
            risk = risk,
            fields = buildList {
                add(ProposalField("Title", e.title))
                add(ProposalField("When", whenText(e)))
                e.location?.let { add(ProposalField("Where", it)) }
                add(ProposalField("Duration", "${e.minutes} min"))
            },
            args = args,
        )
    }

    override suspend fun execute(args: JsonObject): ToolOutcome = withContext(Dispatchers.Main) {
        val e = parse(args)
        val begin = e.start.toInstant().toEpochMilli()
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + e.minutes * 60_000L)
            putExtra(CalendarContract.Events.TITLE, e.title)
            e.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            e.notes?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (ex: ActivityNotFoundException) {
            return@withContext ToolOutcome.Failure("No calendar app found on this phone.")
        }
        ToolOutcome.Success(
            summary = "Calendar opened with the event filled in. The user still has to tap Save there.",
            insertText = summary(e),
        )
    }
}
