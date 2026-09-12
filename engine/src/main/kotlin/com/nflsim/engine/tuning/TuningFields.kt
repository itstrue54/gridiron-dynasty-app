package com.nflsim.engine.tuning

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToLong

/**
 * Every value in a TuningTable, by group, for the tuning screen (SPEC 12).
 *
 * Read through the table's own serializer rather than a hand-kept list, so a
 * coefficient added to the table reaches the screen with nothing else to
 * change. Only numbers are listed.
 */
object TuningFields {

    data class Field(
        val group: String,
        val name: String,
        val value: Double,
        /** The Realistic preset's value: it sets the slider's range. */
        val default: Double,
        val isInt: Boolean,
    ) {
        /** Sliders run from zero to twice the Realistic value. */
        val max: Double get() = if (default > 0.0) default * 2 else 1.0
    }

    private val json = Json { encodeDefaults = true }

    private fun encode(table: TuningTable): JsonObject =
        json.encodeToJsonElement(TuningTable.serializer(), table).jsonObject

    private fun isInt(p: JsonPrimitive) = !p.content.contains('.') && !p.content.contains('e', ignoreCase = true)

    fun list(table: TuningTable): List<Field> {
        val base = encode(TuningTable.REALISTIC)
        return encode(table).flatMap { (group, fields) ->
            val obj = fields as? JsonObject ?: return@flatMap emptyList()
            obj.mapNotNull { (name, v) ->
                val p = v as? JsonPrimitive ?: return@mapNotNull null
                if (p.isString) return@mapNotNull null
                val value = p.content.toDoubleOrNull() ?: return@mapNotNull null
                val default = (base[group] as? JsonObject)?.get(name)?.jsonPrimitive?.content?.toDoubleOrNull() ?: value
                Field(group, name, value, default, isInt(p))
            }
        }
    }

    /** The table with one value changed; an Int value is rounded. */
    fun set(table: TuningTable, group: String, name: String, value: Double): TuningTable {
        val obj = encode(table)
        val g = obj[group] as? JsonObject ?: return table
        val old = g[name] as? JsonPrimitive ?: return table
        val prim = if (isInt(old)) JsonPrimitive(value.roundToLong()) else JsonPrimitive(value.toFloat())
        return json.decodeFromJsonElement(TuningTable.serializer(), JsonObject(obj + (group to JsonObject(g + (name to prim)))))
    }

    /** One group put back to the Realistic preset. */
    fun resetGroup(table: TuningTable, group: String): TuningTable {
        val realistic = encode(TuningTable.REALISTIC)[group] ?: return table
        return json.decodeFromJsonElement(TuningTable.serializer(), JsonObject(encode(table) + (group to realistic)))
    }
}
