package com.mgafk.app.data.websocket

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Minimal RFC 6902 JSON Pointer patch implementation.
 * Operates on kotlinx.serialization JsonElement trees.
 */
object JsonPatch {

    fun decodePointer(path: String): List<String> =
        path.split("/").drop(1).map { it.replace("~1", "/").replace("~0", "~") }

    private fun isNumeric(value: String): Boolean = value.all { it.isDigit() }

    /**
     * Apply a single patch operation to a JsonElement tree.
     * Returns the new root (since JsonElement is immutable).
     */
    fun applyPatch(
        target: JsonElement,
        path: String,
        value: JsonElement?,
        op: String?,
    ): JsonElement {
        val segments = decodePointer(path)
        if (segments.isEmpty()) return value ?: target

        // Skip leading "data" segment like the JS version
        val startIdx = if (segments.firstOrNull() == "data") 1 else 0
        val segs = segments.subList(startIdx, segments.size)
        if (segs.isEmpty()) return value ?: target

        return applyAtPath(target, segs, 0, value, op)
    }

    private fun applyAtPath(
        current: JsonElement,
        segments: List<String>,
        index: Int,
        value: JsonElement?,
        op: String?,
    ): JsonElement {
        val key = segments[index]

        if (index == segments.lastIndex) {
            // Leaf: apply the operation
            return when {
                op == "remove" && current is JsonObject -> {
                    buildJsonObject {
                        current.forEach { (k, v) -> if (k != key) put(k, v) }
                    }
                }
                op == "remove" && current is JsonArray && isNumeric(key) -> {
                    val n = key.toInt()
                    buildJsonArray {
                        current.forEachIndexed { i, v -> if (i != n) add(v) }
                    }
                }
                current is JsonObject -> {
                    buildJsonObject {
                        current.forEach { (k, v) -> put(k, v) }
                        if (value != null) put(key, value)
                    }
                }
                current is JsonArray && isNumeric(key) -> {
                    val n = key.toInt()
                    buildJsonArray {
                        for (i in 0 until maxOf(current.size, n + 1)) {
                            when {
                                i == n && value != null -> add(value)
                                i < current.size -> add(current[i])
                                else -> add(JsonPrimitive(null as String?))
                            }
                        }
                    }
                }
                else -> current
            }
        }

        // Recurse into child
        val nextKey = segments[index + 1]
        return when (current) {
            is JsonObject -> {
                val child = current[key] ?: if (isNumeric(nextKey)) JsonArray(emptyList()) else JsonObject(emptyMap())
                val updated = applyAtPath(child, segments, index + 1, value, op)
                buildJsonObject {
                    current.forEach { (k, v) -> put(k, v) }
                    put(key, updated)
                }
            }
            is JsonArray -> {
                if (isNumeric(key)) {
                    val n = key.toInt()
                    val child = current.getOrNull(n) ?: if (isNumeric(nextKey)) JsonArray(emptyList()) else JsonObject(emptyMap())
                    val updated = applyAtPath(child, segments, index + 1, value, op)
                    buildJsonArray {
                        for (i in 0 until maxOf(current.size, n + 1)) {
                            when {
                                i == n -> add(updated)
                                i < current.size -> add(current[i])
                                else -> add(JsonPrimitive(null as String?))
                            }
                        }
                    }
                } else current
            }
            else -> current
        }
    }
}
