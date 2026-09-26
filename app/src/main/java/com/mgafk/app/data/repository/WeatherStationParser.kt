package com.mgafk.app.data.repository

import com.mgafk.app.data.model.WeatherEvent
import com.mgafk.app.data.model.WeatherForecast
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Turns the `/weather-station` payload into the three cards' data.
 */
object WeatherStationParser {

    fun parse(payload: JsonObject): WeatherForecast {
        val nowEvent = (payload["now"] as? JsonObject)?.let { parseEvent(it) }
        
        val upcomingList = (payload["next"] as? JsonArray ?: payload["upcoming"] as? JsonArray)
            ?.mapNotNull { element -> 
                val obj = element as? JsonObject ?: return@mapNotNull null
                parseEvent(obj)
            }
            ?.sortedBy { it.startsAtMs }
            .orEmpty()

        return WeatherForecast(
            now = nowEvent,
            upcoming = upcomingList
        )
    }

    /**
     * The events of a `/weather-station/next` answer.
     */
    fun parseEvents(payload: JsonObject): List<WeatherEvent> {
        val array = (payload["events"] as? JsonArray) 
            ?: (payload["next"] as? JsonArray)
            ?: (payload["upcoming"] as? JsonArray)
            ?: return emptyList()

        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            parseEvent(obj)
        }.sortedBy { it.startsAtMs }
    }

    /** Null for an entry missing the timestamps the cards count down to. */
    private fun parseEvent(obj: JsonObject): WeatherEvent? {
        val id = obj.string("id") ?: obj.string("weather") ?: return null

        val startsAt = obj.long("started_at") 
            ?: obj.long("startsAtMs") 
            ?: obj.long("startsAt") 
            ?: return null

        val endsAt = obj.long("ended_at") 
            ?: obj.long("endsAtMs") 
            ?: obj.long("endsAt") 
            ?: return null

        return WeatherEvent(
            id = id,
            label = obj.string("weather") ?: obj.string("label") ?: id,
            group = obj.string("group"),
            mutation = obj.string("mutation"),
            spriteUrl = obj.string("sprite") ?: obj.string("spriteUrl"),
            startsAtMs = startsAt,
            endsAtMs = endsAt
        )
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
}
