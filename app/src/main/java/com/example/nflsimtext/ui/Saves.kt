package com.example.nflsimtext.ui

import com.nflsim.data.SaveFile
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.DynastyPhase
import java.io.File

/**
 * Where a dynasty lives on the phone (SPEC 9.1): five slots the user
 * chooses, and three autosaves the game rotates on every phase advance.
 *
 * A dynasty game that loses a save is a dead dynasty game, and one file is
 * one bad write away from that. The autosaves are the belt: the game writes
 * one whenever the phase turns over - a season ending, the offseason
 * running - so the worst a fault can cost is the week it happened in.
 */
class Saves(private val dir: File) {

    data class Card(
        val slot: Int,
        val auto: Boolean,
        val file: File,
        /** Null when the file is there but cannot be read. */
        val club: String?,
        val year: Int,
        val week: Int,
        val phase: DynastyPhase?,
        val record: String,
        val savedAt: Long,
    ) {
        val label: String get() = if (auto) "Autosave $slot" else "Slot $slot"
        val summary: String get() = when {
            club == null -> "Unreadable"
            phase == DynastyPhase.OFFSEASON -> "$club, $year offseason"
            phase == DynastyPhase.PLAYOFFS -> "$club, $year playoffs"
            else -> "$club, $year week $week"
        }
    }

    fun slotFile(slot: Int): File = File(dir, "slot-$slot.sav")

    private fun autoFile(n: Int): File = File(dir, "auto-$n.sav")

    fun occupied(slot: Int): Boolean = slotFile(slot).exists()

    /** Whether there is anything to load at all, without reading any of it. */
    fun any(): Boolean =
        (1..SLOTS).any { occupied(it) } || File(dir, LEGACY_NAME).exists()

    /** Every slot and autosave that has something in it, newest first within each kind. */
    fun cards(): List<Card> {
        val slots = (1..SLOTS).mapNotNull { read(it, false, slotFile(it)) }
        val autos = (1..AUTOSAVES).mapNotNull { read(it, true, autoFile(it)) }
            .sortedByDescending { it.savedAt }
        return slots + autos
    }

    private fun read(slot: Int, auto: Boolean, file: File): Card? {
        if (!file.exists()) return null
        val dynasty = runCatching { SaveFile.decode(file.readBytes()) }.getOrNull()
        return Card(
            slot = slot,
            auto = auto,
            file = file,
            club = dynasty?.team?.name,
            year = dynasty?.year ?: 0,
            week = dynasty?.week ?: 0,
            phase = dynasty?.phase,
            record = dynasty?.record()?.recordText ?: "",
            savedAt = file.lastModified(),
        )
    }

    fun write(slot: Int, dynasty: Dynasty) {
        dir.mkdirs()
        slotFile(slot).writeBytes(SaveFile.encode(dynasty))
    }

    fun load(file: File): Dynasty = SaveFile.decode(file.readBytes())

    fun delete(file: File): Boolean = file.delete()

    /**
     * Rotates the autosaves: the oldest of the three is the one overwritten,
     * so there are always three phases of history to fall back on.
     */
    fun autosave(dynasty: Dynasty): File {
        dir.mkdirs()
        val target = (1..AUTOSAVES).map(::autoFile)
            .minByOrNull { if (it.exists()) it.lastModified() else 0L }!!
        target.writeBytes(SaveFile.encode(dynasty))
        return target
    }

    /**
     * The single save older builds wrote becomes slot 1, once. A dynasty
     * carried over from before slots existed is not something to lose.
     */
    fun adoptLegacySave(): Boolean {
        val legacy = File(dir, LEGACY_NAME)
        if (!legacy.exists() || occupied(1)) return false
        return legacy.renameTo(slotFile(1))
    }

    companion object {
        const val SLOTS = 5
        const val AUTOSAVES = 3
        private const val LEGACY_NAME = "dynasty.sav"
    }
}
