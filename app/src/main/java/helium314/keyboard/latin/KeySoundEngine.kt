// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Plays the custom key sounds. Some packs are plain (one sound per key type), others react to
 * what you type: speed, the letters, the word you just finished, autocorrect, delete streaks...
 *
 * Pitch changes use SoundPool's playback rate, which Android limits to 0.5 (an octave down) .. 2.0 (an octave up).
 */
object KeySoundEngine {
    private enum class Kind { KEY, SPACE, DELETE, ENTER }

    /** Plain packs: key, space, delete, enter, then optional extra key variants. */
    private val STATIC_PACKS: Map<String, IntArray> = mapOf(
        "typewriter" to intArrayOf(R.raw.snd_typewriter_key, R.raw.snd_typewriter_space, R.raw.snd_typewriter_delete, R.raw.snd_typewriter_enter),
        "retro" to intArrayOf(R.raw.snd_retro_key, R.raw.snd_retro_space, R.raw.snd_retro_delete, R.raw.snd_retro_enter),
        "bubble" to intArrayOf(R.raw.snd_bubble_key, R.raw.snd_bubble_space, R.raw.snd_bubble_delete, R.raw.snd_bubble_enter),
        "thock" to intArrayOf(R.raw.snd_thock_key, R.raw.snd_thock_space, R.raw.snd_thock_delete, R.raw.snd_thock_enter),
        "piano" to intArrayOf(R.raw.snd_piano_key, R.raw.snd_piano_space, R.raw.snd_piano_delete, R.raw.snd_piano_enter,
            R.raw.snd_piano_key0, R.raw.snd_piano_key1, R.raw.snd_piano_key2, R.raw.snd_piano_key3, R.raw.snd_piano_key4),
        "rain" to intArrayOf(R.raw.snd_rain_key, R.raw.snd_rain_space, R.raw.snd_rain_delete, R.raw.snd_rain_enter),
        "laser" to intArrayOf(R.raw.snd_laser_key, R.raw.snd_laser_space, R.raw.snd_laser_delete, R.raw.snd_laser_enter),
        "wood" to intArrayOf(R.raw.snd_wood_key, R.raw.snd_wood_space, R.raw.snd_wood_delete, R.raw.snd_wood_enter),
        "arcade" to intArrayOf(R.raw.snd_arcade_key, R.raw.snd_arcade_space, R.raw.snd_arcade_delete, R.raw.snd_arcade_enter),
        "chimes" to intArrayOf(R.raw.snd_chimes_key, R.raw.snd_chimes_space, R.raw.snd_chimes_delete, R.raw.snd_chimes_enter),
        "drums" to intArrayOf(R.raw.snd_drums_key, R.raw.snd_drums_space, R.raw.snd_drums_delete, R.raw.snd_drums_enter),
        "wet" to intArrayOf(R.raw.snd_wet_key, R.raw.snd_wet_space, R.raw.snd_wet_delete, R.raw.snd_wet_enter),
        "haunted" to intArrayOf(R.raw.snd_haunted_key, R.raw.snd_haunted_space, R.raw.snd_haunted_delete, R.raw.snd_haunted_enter),
        "underwater" to intArrayOf(R.raw.snd_underwater_key, R.raw.snd_underwater_space, R.raw.snd_underwater_delete, R.raw.snd_underwater_enter),
        "space" to intArrayOf(R.raw.snd_space_key, R.raw.snd_space_space, R.raw.snd_space_delete, R.raw.snd_space_enter),
        "creatures" to intArrayOf(R.raw.snd_creature_key, R.raw.snd_creature_space, R.raw.snd_creature_delete, R.raw.snd_creature_enter),
        "glitch" to intArrayOf(R.raw.snd_glitch_key, R.raw.snd_glitch_space, R.raw.snd_glitch_delete, R.raw.snd_glitch_enter),
        "party" to intArrayOf(R.raw.snd_party_key, R.raw.snd_party_space, R.raw.snd_party_delete, R.raw.snd_party_enter),
    )

    /** Samples needed by the reactive packs. */
    private val REACTIVE_PACKS: Map<String, IntArray> = mapOf(
        "speeddj" to intArrayOf(R.raw.snd_drums_key, R.raw.snd_drums_space, R.raw.snd_drums_enter, R.raw.snd_drums_delete),
        "spell" to intArrayOf(R.raw.snd_note_piano),
        "mood" to intArrayOf(R.raw.snd_mood_key, R.raw.snd_mood_angry, R.raw.snd_mood_happy, R.raw.snd_mood_neutral),
        "gameshow" to intArrayOf(R.raw.snd_gs_tick, R.raw.snd_gs_space, R.raw.snd_gs_ding, R.raw.snd_gs_buzzer),
        "combo" to intArrayOf(R.raw.snd_combo_hit, R.raw.snd_combo_milestone, R.raw.snd_combo_break),
        "regret" to intArrayOf(R.raw.snd_mood_key, R.raw.snd_mood_neutral, R.raw.snd_regret_no),
        "chords" to intArrayOf(R.raw.snd_note_pluck),
        "theremin" to intArrayOf(R.raw.snd_note_sine),
        "kalimba" to intArrayOf(R.raw.snd_note_kalimba),
        "steeldrum" to intArrayOf(R.raw.snd_note_steel),
        "opera" to intArrayOf(R.raw.snd_note_vowel, R.raw.snd_opera_enter),
        "random" to STATIC_PACKS.values.flatMap { it.take(4) }.toIntArray(),
    )

    val CUSTOM_SOUND_NAMES = arrayOf("key", "space", "delete", "enter")

    fun getCustomSoundDir(context: Context) = File(DeviceProtectedUtils.getFilesDir(context), "custom_sounds")

    private val ANGRY = setOf("wtf", "ugh", "fuck", "fucking", "shit", "damn", "hate", "angry", "mad", "stupid",
        "annoying", "smh", "bruh", "ffs", "dumb", "pissed", "ugh", "argh", "kill", "worst", "idiot", "crap")
    private val HAPPY = setOf("love", "lol", "lmao", "haha", "hahaha", "yay", "yes", "awesome", "amazing", "nice",
        "thanks", "thank", "cool", "great", "happy", "beautiful", "best", "omg", "xoxo", "cute", "sweet", "perfect")

    // semitone offsets (C pentatonic) that stay within the 0.5 .. 2.0 rate range
    private val PENTATONIC = intArrayOf(-12, -10, -8, -5, -3, 0, 2, 4, 7, 9, 12)
    // C, G, Am, F as semitones from C
    private val CHORDS = arrayOf(intArrayOf(0, 4, 7), intArrayOf(-5, -1, 2), intArrayOf(-3, 0, 4), intArrayOf(-7, -3, 0))

    private val handler = Handler(Looper.getMainLooper())
    private var pool: SoundPool? = null
    private val ids = HashMap<Int, Int>() // raw resource -> SoundPool id
    private var loadedPack: String? = null
    private var customIds = IntArray(4)

    // state for reactive packs
    private var lastPressTime = 0L
    private var avgInterval = 250f
    private var beatStep = 0
    private val word = StringBuilder()
    private var combo = 0
    private var deleteStreak = 0
    private var lastDeleteTime = 0L
    private var regretStream = 0
    private var thereminStream = 0
    private var chordIndex = 0
    private var lastNote = 0

    /** Call after custom sound files change, so they get loaded again. */
    @JvmStatic
    fun reload() {
        pool?.release()
        pool = null
        ids.clear()
        loadedPack = null
    }

    private var poolUsesMedia = false

    private fun ensurePool(): SoundPool = pool ?: SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(if (poolUsesMedia) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build())
        .build().also { pool = it }

    private fun prepare(context: Context, pack: String) {
        if (pack == loadedPack) return
        val p = ensurePool()
        loadedPack = pack
        if (pack == "custom") {
            val dir = getCustomSoundDir(context)
            customIds = IntArray(4) { i ->
                val f = File(dir, CUSTOM_SOUND_NAMES[i])
                if (f.exists()) p.load(f.absolutePath, 1) else 0
            }
            return
        }
        val res = STATIC_PACKS[pack] ?: REACTIVE_PACKS[pack] ?: return
        res.forEach { r -> if (r !in ids) ids[r] = p.load(context, r, 1) }
    }

    private fun play(res: Int, volume: Float, rate: Float = 1f, delayMs: Long = 0): Int {
        val p = pool ?: return 0
        val id = ids[res] ?: return 0
        val r = rate.coerceIn(0.5f, 2f)
        val v = volume.coerceIn(0f, 1f)
        if (delayMs > 0) {
            handler.postDelayed({ pool?.play(id, v, v, 1, 0, r) }, delayMs)
            return 0
        }
        return p.play(id, v, v, 1, 0, r)
    }

    private fun semitones(n: Int) = 2f.pow(n / 12f)

    private fun kindOf(code: Int) = when (code) {
        Constants.CODE_SPACE -> Kind.SPACE
        KeyCode.DELETE -> Kind.DELETE
        Constants.CODE_ENTER -> Kind.ENTER
        else -> Kind.KEY
    }

    /** Plays the sound for a key press. Returns false if the system click should be used instead. */
    @JvmStatic
    fun play(context: Context, pack: String?, code: Int, volume: Float, mediaStream: Boolean): Boolean {
        if (pack == null || pack == "system") return false
        if (mediaStream != poolUsesMedia) {
            // silent mode mutes system sounds; switch between the system and media stream when needed
            reload()
            poolUsesMedia = mediaStream
        }
        prepare(context, pack)
        val kind = kindOf(code)
        trackState(code, kind)
        if (pack == "custom") {
            val id = customIds[kind.ordinal].takeIf { it != 0 } ?: customIds[0]
            if (id == 0) return false
            pool?.play(id, volume, volume, 1, 0, 1f)
            return true
        }
        STATIC_PACKS[pack]?.let { res ->
            playStatic(pack, res, kind, code, volume)
            return true
        }
        when (pack) {
            "speeddj" -> speedDj(kind, volume)
            "spell" -> spell(kind, code, volume)
            "mood" -> mood(kind, volume)
            "gameshow" -> when (kind) {
                Kind.KEY -> play(R.raw.snd_gs_tick, volume * 0.6f)
                Kind.SPACE -> play(R.raw.snd_gs_space, volume * 0.6f)
                Kind.DELETE -> play(R.raw.snd_gs_tick, volume * 0.6f, 0.7f)
                Kind.ENTER -> play(R.raw.snd_gs_ding, volume)
            }
            "combo" -> combo(kind, volume)
            "regret" -> regret(kind, volume)
            "chords" -> chords(kind, volume)
            "theremin" -> theremin(kind, code, volume)
            "kalimba" -> melodic(R.raw.snd_note_kalimba, kind, volume)
            "steeldrum" -> melodic(R.raw.snd_note_steel, kind, volume)
            "opera" -> if (kind == Kind.ENTER) play(R.raw.snd_opera_enter, volume)
                else melodic(R.raw.snd_note_vowel, kind, volume * 0.8f)
            "random" -> {
                val res = STATIC_PACKS.values.random()
                play(res[kind.ordinal], volume)
            }
            else -> return false
        }
        return true
    }

    /** Called when autocorrect replaces the typed word. */
    @JvmStatic
    fun onAutoCorrect(pack: String?, volume: Float) {
        if (pack != "gameshow") return
        play(R.raw.snd_gs_buzzer, volume * 0.8f, delayMs = 60)
    }

    private fun trackState(code: Int, kind: Kind) {
        val now = SystemClock.uptimeMillis()
        val interval = (now - lastPressTime).coerceAtMost(2000)
        lastPressTime = now
        avgInterval = avgInterval * 0.7f + interval * 0.3f
        when (kind) {
            Kind.KEY -> if (Character.isLetterOrDigit(code) || code == '\''.code) word.appendCodePoint(code) else word.setLength(0)
            Kind.DELETE -> if (word.isNotEmpty()) word.setLength(word.length - 1)
            else -> {} // word is read and cleared by the packs that use it
        }
    }

    private fun playStatic(pack: String, res: IntArray, kind: Kind, code: Int, volume: Float) {
        var sound = res[kind.ordinal]
        if (kind == Kind.KEY && res.size > 4) // piano: same key, same note
            sound = res[4 + Math.floorMod(code, res.size - 4)]
        when (pack) {
            "haunted" -> {
                play(sound, volume, Random.nextFloat() * 0.2f + 0.9f)
                play(sound, volume * 0.25f, 0.8f, delayMs = 900) // the ghost echo
            }
            "creatures" -> play(sound, volume, 0.8f + Random.nextFloat() * 0.6f)
            "glitch" -> {
                play(sound, volume, 0.6f + Random.nextFloat() * 1.2f)
                if (Random.nextFloat() < 0.3f) repeat(Random.nextInt(1, 3)) { i ->
                    play(sound, volume * 0.7f, 0.6f + Random.nextFloat() * 1.2f, delayMs = 40L * (i + 1))
                }
            }
            else -> play(sound, volume)
        }
        if (kind != Kind.KEY && kind != Kind.DELETE) word.setLength(0)
    }

    /** A beat that follows how fast you type. */
    private fun speedDj(kind: Kind, volume: Float) {
        // ~150 ms between keys is fast typing, ~400 ms is slow
        val rate = (220f / max(avgInterval, 80f)).coerceIn(0.8f, 1.5f)
        when (kind) {
            Kind.KEY -> {
                val step = beatStep++ % 8
                when (step) {
                    0, 5 -> play(R.raw.snd_drums_space, volume, rate)      // kick
                    2, 6 -> play(R.raw.snd_drums_enter, volume * 0.8f, rate) // snare
                    else -> play(R.raw.snd_drums_key, volume * 0.6f, rate)   // hat
                }
            }
            Kind.SPACE -> play(R.raw.snd_drums_space, volume, rate)
            Kind.DELETE -> play(R.raw.snd_drums_delete, volume, rate)
            Kind.ENTER -> {
                play(R.raw.snd_drums_space, volume)
                play(R.raw.snd_drums_enter, volume, delayMs = 120)
                beatStep = 0
            }
        }
        if (kind == Kind.SPACE || kind == Kind.ENTER) word.setLength(0)
    }

    /** Every letter is a fixed note, so words turn into melodies. */
    private fun spell(kind: Kind, code: Int, volume: Float) {
        when (kind) {
            Kind.KEY -> {
                val c = Character.toLowerCase(code)
                val index = if (c in 'a'.code..'z'.code) c - 'a'.code else Math.floorMod(code, 26)
                play(R.raw.snd_note_piano, volume, semitones(PENTATONIC[index % PENTATONIC.size]))
            }
            Kind.SPACE -> play(R.raw.snd_note_piano, volume * 0.5f, 0.5f)
            Kind.DELETE -> play(R.raw.snd_note_piano, volume * 0.6f, semitones(-1))
            Kind.ENTER -> {
                play(R.raw.snd_note_piano, volume, 1f)
                play(R.raw.snd_note_piano, volume, semitones(4), 40)
                play(R.raw.snd_note_piano, volume, semitones(7), 80)
            }
        }
        if (kind == Kind.SPACE || kind == Kind.ENTER) word.setLength(0)
    }

    /** Reacts to the word you just finished. */
    private fun mood(kind: Kind, volume: Float) {
        when (kind) {
            Kind.KEY, Kind.DELETE -> play(R.raw.snd_mood_key, volume * 0.7f, if (kind == Kind.DELETE) 0.8f else 1f)
            Kind.SPACE, Kind.ENTER -> {
                val w = word.toString().lowercase()
                when {
                    w in ANGRY -> play(R.raw.snd_mood_angry, volume)
                    w in HAPPY -> play(R.raw.snd_mood_happy, volume)
                    else -> play(R.raw.snd_mood_neutral, volume * 0.7f)
                }
                word.setLength(0)
            }
        }
    }

    /** Builds up while you type without deleting, breaks when you delete. */
    private fun combo(kind: Kind, volume: Float) {
        if (kind == Kind.DELETE) {
            if (combo >= 10) play(R.raw.snd_combo_break, volume) else play(R.raw.snd_combo_hit, volume * 0.5f, 0.6f)
            combo = 0
            return
        }
        combo++
        if (combo == 25 || combo == 50 || combo == 100 || (combo > 100 && combo % 100 == 0))
            play(R.raw.snd_combo_milestone, volume)
        else
            play(R.raw.snd_combo_hit, volume * 0.7f, 1f + min(combo, 60) / 60f)
        if (kind != Kind.KEY) word.setLength(0)
    }

    /** Holding delete makes a whine that gets lower and sadder. */
    private fun regret(kind: Kind, volume: Float) {
        if (kind != Kind.DELETE) {
            deleteStreak = 0
            play(if (kind == Kind.KEY) R.raw.snd_mood_key else R.raw.snd_mood_neutral, volume * 0.6f)
            if (kind != Kind.KEY) word.setLength(0)
            return
        }
        val now = SystemClock.uptimeMillis()
        deleteStreak = if (now - lastDeleteTime < 450) deleteStreak + 1 else 0
        lastDeleteTime = now
        if (regretStream != 0) pool?.stop(regretStream)
        regretStream = play(R.raw.snd_regret_no, volume, max(0.5f, 1.15f - deleteStreak * 0.06f))
    }

    /** Letters play notes of the current chord, space strums it and moves to the next chord. */
    private fun chords(kind: Kind, volume: Float) {
        val chord = CHORDS[chordIndex % CHORDS.size]
        when (kind) {
            Kind.KEY -> play(R.raw.snd_note_pluck, volume * 0.6f, semitones(chord.random()))
            Kind.DELETE -> play(R.raw.snd_note_pluck, volume * 0.4f, semitones(chord[0] - 5))
            Kind.SPACE, Kind.ENTER -> {
                chord.forEachIndexed { i, n -> play(R.raw.snd_note_pluck, volume, semitones(n), 35L * i) }
                if (kind == Kind.ENTER) play(R.raw.snd_note_pluck, volume, semitones(chord[0] + 12), 140)
                chordIndex++
                word.setLength(0)
            }
        }
    }

    /** One warbly tone that jumps to a new pitch with every key. */
    private fun theremin(kind: Kind, code: Int, volume: Float) {
        if (thereminStream != 0) pool?.stop(thereminStream)
        val semis = when (kind) {
            Kind.KEY -> {
                val c = Character.toLowerCase(code)
                (if (c in 'a'.code..'z'.code) c - 'a'.code else Math.floorMod(code, 26)) - 13
            }
            Kind.SPACE -> -12
            Kind.DELETE -> -7
            Kind.ENTER -> 12
        }
        thereminStream = play(R.raw.snd_note_sine, volume * 0.7f, semitones(semis.coerceIn(-12, 12)))
        if (kind == Kind.SPACE || kind == Kind.ENTER) word.setLength(0)
    }

    /** Random notes from a scale that always sounds good together. */
    private fun melodic(res: Int, kind: Kind, volume: Float) {
        when (kind) {
            Kind.KEY -> {
                var n: Int
                do { n = PENTATONIC[Random.nextInt(3, PENTATONIC.size)] } while (n == lastNote)
                lastNote = n
                play(res, volume, semitones(n))
            }
            Kind.SPACE -> play(res, volume * 0.8f, semitones(PENTATONIC[Random.nextInt(0, 3)]))
            Kind.DELETE -> play(res, volume * 0.6f, semitones(-7))
            Kind.ENTER -> {
                play(res, volume, 1f)
                play(res, volume, semitones(7), 90)
                play(res, volume, semitones(12), 180)
            }
        }
        if (kind == Kind.SPACE || kind == Kind.ENTER) word.setLength(0)
    }
}
