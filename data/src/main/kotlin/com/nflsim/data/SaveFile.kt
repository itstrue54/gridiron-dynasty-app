package com.nflsim.data

import com.nflsim.engine.season.Dynasty
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Turning a dynasty into bytes and back.
 *
 * Deliberately knows nothing about Android: it hands back a ByteArray and the
 * app decides where to put it. That keeps :data testable on the JVM and keeps
 * file handling where it belongs.
 *
 * CBOR rather than JSON because a league carries 1,700 players with sixty
 * ratings each, and gzip on top of a binary format is roughly a third the size.
 */
@OptIn(ExperimentalSerializationApi::class)
object SaveFile {

    private val cbor = Cbor { ignoreUnknownKeys = true }

    fun encode(dynasty: Dynasty): ByteArray {
        val raw = cbor.encodeToByteArray(Envelope.serializer(),
            Envelope(CURRENT_SAVE_VERSION, dynasty))
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Dynasty {
        val raw = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        val envelope = cbor.decodeFromByteArray(Envelope.serializer(), raw)
        return migrate(envelope)
    }

    /**
     * Save migration. Every model change that alters the shape of a Dynasty
     * needs a step here, in the same commit - a dynasty game that eats saves
     * on update is a dead dynasty game (docs/SPEC.md 9.1).
     */
    private fun migrate(envelope: Envelope): Dynasty = when (envelope.version) {
        CURRENT_SAVE_VERSION -> envelope.dynasty
        else -> error(
            "save was written by version ${envelope.version}, this build reads $CURRENT_SAVE_VERSION")
    }

    @kotlinx.serialization.Serializable
    private data class Envelope(val version: Int, val dynasty: Dynasty)
}
