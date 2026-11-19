package com.hermes.client.ui.plugins.achievements

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

data class Achievement(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    /** "unlocked", "discovered" (visible, not yet earned) or "secret". */
    val state: String,
    val tier: String?,
    val nextTier: String?,
    val progressPct: Int,
    val criteria: String,
    val unlockedAt: Long?,
) {
    val unlocked: Boolean get() = state == "unlocked"
}

data class AchievementsSnapshot(
    val achievements: List<Achievement>,
    val unlockedCount: Int,
    val totalCount: Int,
    val secretCount: Int,
    val isStale: Boolean,
    /** Scan state from `scan_meta.status.state`, e.g. "idle" or "running"; null if absent. */
    val scanState: String?,
) {
    val scanning: Boolean get() = scanState == "running" || scanState == "scanning"
}

/** Tier order used for badge colour and sorting; unknown tiers sort last. */
val TIER_ORDER = listOf("Copper", "Silver", "Gold", "Diamond", "Olympian")

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull

/** Lenient parse of `GET /achievements`: unknown or missing fields never fail the screen. */
fun parseAchievements(body: String, json: Json = Json): AchievementsSnapshot {
    val root = json.parseToJsonElement(body) as? JsonObject ?: error("unexpected response")
    val list = (root["achievements"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val id = o["id"].str() ?: return@mapNotNull null
        Achievement(
            id = id,
            name = o["name"].str() ?: id,
            description = o["description"].str().orEmpty(),
            category = o["category"].str() ?: "Other",
            state = o["state"].str() ?: "discovered",
            tier = o["tier"].str(),
            nextTier = o["next_tier"].str(),
            progressPct = (o["progress_pct"].int() ?: 0).coerceIn(0, 100),
            criteria = o["criteria"].str().orEmpty(),
            unlockedAt = o["unlocked_at"].long(),
        )
    }
    val status = ((root["scan_meta"] as? JsonObject)?.get("status") as? JsonObject)
    return AchievementsSnapshot(
        achievements = list,
        unlockedCount = root["unlocked_count"].int() ?: list.count { it.unlocked },
        totalCount = root["total_count"].int() ?: list.size,
        secretCount = root["secret_count"].int() ?: 0,
        isStale = (root["is_stale"] as? JsonPrimitive)?.booleanOrNull ?: false,
        scanState = status?.get("state").str(),
    )
}
