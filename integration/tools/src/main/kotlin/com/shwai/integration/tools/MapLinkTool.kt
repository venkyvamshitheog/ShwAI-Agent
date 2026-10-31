package com.shwai.integration.tools

import com.shwai.sdk.orchestration.ActionProposal
import com.shwai.sdk.orchestration.AgentTool
import com.shwai.sdk.orchestration.ProposalField
import com.shwai.sdk.orchestration.RiskLevel
import com.shwai.sdk.orchestration.ToolOutcome
import java.net.URLEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Pure link builder: no network, no permissions, nothing is opened. The link can be inserted into the field. */
class MapLinkTool : AgentTool {
    override val name = "create_map_link"
    override val description =
        "Build a Google Maps link for a place name or address. Opens nothing; the link can be inserted into the text field."
    override val risk = RiskLevel.READ_ONLY
    override val parameters = objectSchema(listOf("place"), "place" to str("Place name or address"))

    override suspend fun propose(args: JsonObject) = ActionProposal(
        name, "Map link", risk, listOf(ProposalField("Place", args.string("place").orEmpty())), args,
    )

    override suspend fun execute(args: JsonObject): ToolOutcome {
        val place = args.string("place")?.trim().orEmpty()
        if (place.isEmpty() || place.length > 200) return ToolOutcome.Failure("Place is missing or too long.")
        val url = "https://www.google.com/maps/search/?api=1&query=" + URLEncoder.encode(place, "UTF-8")
        return ToolOutcome.Success(
            summary = "Map link ready",
            data = buildJsonObject { put("url", url) },
            insertText = "\uD83D\uDCCD $place\n$url",
        )
    }
}
