#!/usr/bin/env /opt/msu/kotlinc/bin/kotlin

import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

// Special thanks to:
//   https://problemkaputt.de/fullsnes.htm#snesapudspbrrsamples
//   https://problemkaputt.de/fullsnes.htm#snesapudspadsrgainenvelope

// Important offsets in "Super Metroid.sfc":
//   0x2E2F84 - The last Metroid
//   0x2E5558 - is in captivity
//   0x29B166 - Etecoons' Cry (loop point at block index 176) (try 177 so we get filter 0) (ADSR currently 0x8FD0)

/**
 * Represents an Attack/Decay/Sustain/Release envelope. The envelope starts at zero and increases to full volume at the
 * given [attackRate]. It then begins decaying at the given [decayRate] until it reaches the given [sustainLevel]. It
 * then switches to decaying at the given [sustainRate], before finally releasing and returning to zero.
 */
data class AdsrEnvelope(
    val attackRate: Int,
    val decayRate: Int,
    override val level: Int = 0,
    val phase: Phase = Phase.ATTACK,
    val sample: Int = 0,
    val sustainLevel: Int,
    val sustainRate: Int
) : Envelope {
    enum class Phase {
        ATTACK,
        DECAY,
        SUSTAIN,
        RELEASE
    }

    private val attackSamples = samplesPerTick[attackRate * 2 + 1]

    private val decaySamples = samplesPerTick[decayRate * 2 + 16]

    private val sustainBoundary = (sustainLevel + 1) * 0x100

    private val sustainSamples = samplesPerTick[sustainRate]

    override fun nextSample() = when (phase) {
        Phase.ATTACK -> {
            val newSample = sample + 1

            if (newSample == attackSamples) {
                val levelChange = if (attackRate == 0x1f) 0x400 else 0x20
                val newLevel = clamp(level + levelChange)
                val newPhase = if (newLevel >= 0x7e0) Phase.DECAY else phase

                this.copy(
                    level = newLevel,
                    phase = newPhase,
                    sample = 0
                )
            } else {
                this.copy(sample = newSample)
            }
        }
        Phase.DECAY -> {
            val newSample = sample + 1

            if (newSample == decaySamples) {
                val newLevel = (level - 1).let { it - (it shr 8) }.let(::clamp)
                val newPhase = if (newLevel <= sustainBoundary) Phase.SUSTAIN else phase

                this.copy(
                    level = newLevel,
                    phase = newPhase,
                    sample = 0
                )
            } else {
                this.copy(sample = newSample)
            }
        }
        Phase.SUSTAIN -> {
            val newSample = sample + 1

            if (newSample == sustainSamples) {
                val newLevel = (level - 1).let { it - (it shr 8) }.let(::clamp)
                val newPhase = if (newLevel == 0) Phase.RELEASE else phase

                this.copy(
                    level = newLevel,
                    phase = newPhase,
                    sample = 0
                )
            } else {
                this.copy(sample = newSample)
            }
        }
        Phase.RELEASE -> {
            val newLevel = clamp(level - 8)

            this.copy(level = newLevel)
        }
    }
}

/**
 * Represents a nine-byte block of Bit-Rate Reduction (BRR) compressed data.
 */
data class BrrBlock(
    val end: Boolean,
    val filter: Int,
    val loop: Boolean,
    val nibbles: List<Nibble>,
    val range: Int
)

/**
 * Represents a Custom Gain envelope, allowing for relatively simple, one-way volume slides.
 */
data class CustomGainEnvelope(
    val mode: Mode,
    override val level: Int = mode.initialLevel,
    val rate: Int,
    val sample: Int = 0
) : Envelope {
    enum class Mode(val initialLevel: Int) {
        LINEAR_DECREASE(0x7ff),
        EXPONENTIAL_DECREASE(0x7ff),
        LINEAR_INCREASE(0),
        BENT_INCREASE(0)
    }

    val samples = samplesPerTick[rate]

    override fun nextSample(): Envelope {
        val newSample = sample + 1

        return if (newSample == samples) {
            val levelChange = when (mode) {
                Mode.LINEAR_DECREASE -> -32
                Mode.EXPONENTIAL_DECREASE -> -(((level - 1) shr 8) + 1)
                Mode.LINEAR_INCREASE -> +32
                Mode.BENT_INCREASE -> if (level < 0x600) level + 32 else level + 8
            }

            this.copy(
                level = clamp(level + levelChange),
                sample = 0
            )
        } else {
            this.copy(sample = newSample)
        }
    }
}

/**
 * Represents a Direct Gain envelope, which sets a constant volume at the given [level].
 */
data class DirectGainEnvelope(
    override val level: Int
) : Envelope {
    override fun nextSample() = this
}

/**
 * Represents an envelope supported by the SNES sound chip.
 */
interface Envelope {
    /**
     * The current volume level of this envelope.
     */
    val level: Int

    /**
     * Clamps the given [level] so it is between 0 and 0x7ff, inclusive.
     */
    fun clamp(level: Int) = level.coerceIn(0, 0x7ff)

    /**
     * Advances this envelope by one sample and returns updated values, if any "tick" happened.
     */
    fun nextSample(): Envelope
}

/**
 * Represents a four-bit nibble of compressed sample data.
 */
@JvmInline
value class Nibble(private val value: Int) {
    /**
     * Extends the sign bit of this nibble so the whole [Int] value matches it.
     */
    fun signExtended(): Int = if (value > 7) 0 - 16 + value else value

    override fun toString() = this.signExtended().toString()
}

/**
 * Represents a stream of decompressed sample data, which can loop infinitely.
 */
class SampleIterator : Iterator<Int> {
    private var brrBlockIndex = 0
    private var nibbleIndex = 0

    private var old = 0
    private var older = 0

    override fun hasNext() = brrBlockIndex < brrBlocks.size

    override fun next(): Int {
        val brrBlock = brrBlocks[brrBlockIndex]
        val nibble = brrBlock.nibbles[nibbleIndex]

        nibbleIndex++

        if (nibbleIndex == brrBlock.nibbles.size) {
            brrBlockIndex++
            nibbleIndex = 0
        }

        val sample = (nibble.signExtended() shl brrBlock.range) shr 1

        val new = when (brrBlock.filter) {
            0 -> sample
            1 -> sample + old * 0.9375
            2 -> sample + old * 1.90625 - older * 0.9375
            3 -> sample + old * 1.796875 - older * 0.8125
            else -> error("Invalid filter: ${brrBlock.filter}")
        }.toInt()

        older = old
        old = new

        return new
    }

    /**
     * Resets the stream to the first nibble in the given [brrBlockIndex].
     */
    fun loop(brrBlockIndex: Int) {
        this.brrBlockIndex = brrBlockIndex
        nibbleIndex = 0
    }
}

var adsr: Int? = null

val bitsPerSample = 16

val brrBlocks: List<BrrBlock> by lazy { loadBrrBlocks() }

val channels = 1

var endOverride: Int? = null

var gain: Int? = null

var inputFile = ""

var loopPoint: Int? = null

var offset = 0

var outputFile: String? = null

var play = false

var print = false

var sampleRate = 32000

val samples: List<Int> by lazy {
    SampleIterator().asSequence().toList()
}

val samplesPerTick = listOf(
    Integer.MAX_VALUE,
    2048,
    1536,
    1280,
    1024,
    768,
    640,
    512,
    384,
    320,
    256,
    192,
    160,
    128,
    96,
    80,
    64,
    48,
    40,
    32,
    24,
    20,
    16,
    12,
    10,
    8,
    6,
    5,
    4,
    3,
    2,
    1
)

fun createEnvelope(): Envelope {
    val localAdsr = adsr
    val localGain = gain

    return if (localAdsr != null) {
        AdsrEnvelope(
            attackRate = localAdsr shr 8 and 0xF,
            decayRate = localAdsr shr 12 and 0x7,
            sustainLevel = localAdsr shr 5 and 0x7,
            sustainRate = localAdsr and 0x1F
        )
    } else if (localGain != null) {
        if (localGain and 0b10000000 == 0) {
            DirectGainEnvelope(localGain shl 4)
        } else {
            CustomGainEnvelope(
                mode = CustomGainEnvelope.Mode.entries[localGain shr 5 and 3],
                rate = localGain and 0x1F
            )
        }
    } else {
        // Use maximum volume if no envelope was specified.
        DirectGainEnvelope(0x800)
    }
}

/**
 * Reads and returns the compressed BRR blocks from the [inputFile].
 */
fun loadBrrBlocks(): List<BrrBlock> {
    RandomAccessFile(inputFile, "r").use { file ->
        file.skipBytes(offset)

        var ended = false

        val brrBlocks = generateSequence {
            if (ended) {
                null
            } else {
                val header = file.read()
                val end = header and 1 == 1
                val filter = header shr 2 and 3
                val loop = header and 2 == 2
                val range = header shr 4

                val nibbles = (0 until 8).flatMap {
                    val byte = file.read()

                    listOf(Nibble(byte shr 4), Nibble(byte and 0b00001111))
                }

                if (end) {
                    @Suppress("AssignedValueIsNeverRead") // Yes it is
                    ended = true
                }

                BrrBlock(end, filter, loop, nibbles, range)
            }
        }

        return endOverride?.let { brrBlocks.take(it).toList() } ?: brrBlocks.toList()
    }
}

fun openAudio(): SourceDataLine {
    val audioFormat = AudioFormat(sampleRate.toFloat(), bitsPerSample, channels, true, false)
    val sourceDataLine = AudioSystem.getSourceDataLine(audioFormat)

    sourceDataLine.open()
    sourceDataLine.start()
    Thread.sleep(100)

    return sourceDataLine
}

/**
 * Writes the given [value] as four little-endian bytes.
 */
fun OutputStream.writeInt(value: Int) {
    write(value)
    write(value shr 8)
    write(value shr 16)
    write(value shr 24)
}

/**
 * Writes the given [value] as two little-endian bytes.
 */
fun OutputStream.writeShort(value: Int) {
    write(value)
    write(value shr 8)
}

/**
 * Writes the bytes representing the characters in the given [value] (assuming they *can* be represented as bytes).
 */
fun OutputStream.writeString(value: String) {
    for (char in value) {
        write(char.code)
    }
}

/**
 * Writes the decompressed samples to the given [outputFile], in WAV audio format.
 */
fun outputSamples(outputFile: String) {
    FileOutputStream(outputFile).use { out ->
        val sampleSize = samples.size * 2

        out.writeString("RIFF")

        // File size minus 8 (Sample size + the rest of this header)
        out.writeInt(sampleSize + 36)

        out.writeString("WAVE")

        val bytesPerBlock = channels * bitsPerSample / 8
        val bytesPerSecond = sampleRate * bytesPerBlock

        out.writeString("fmt ")
        out.writeInt(16) // Chunk size minus 8
        out.writeShort(1) // Audio format (PCM)
        out.writeShort(channels)
        out.writeInt(sampleRate)
        out.writeInt(bytesPerSecond)
        out.writeShort(bytesPerBlock)
        out.writeShort(bitsPerSample)

        out.writeString("data")
        out.writeInt(sampleSize)

        samples.forEach { out.writeShort(it) }
    }
}

/**
 * Plays the decompressed samples to the speakers, respecting any specified loop point and envelope.
 */
fun playAudio() {
    val audio = openAudio()
    var envelope = createEnvelope()

    println(envelope)

    val sampleIterator = SampleIterator()

    while (sampleIterator.hasNext()) {
        val sample = sampleIterator.next() * envelope.level / 0x800
        val byte1 = (sample and 0xff).toByte()
        val byte2 = (sample shr 8).toByte()

        audio.write(listOf(byte1, byte2).toByteArray(), 0, 2)

        envelope = envelope.nextSample()

        // Reset the loop if we've hit the end of the sample and our envelope hasn't reached zero yet.
        if (!sampleIterator.hasNext() && loopPoint != null && envelope.level != 0) {
            sampleIterator.loop(loopPoint!!)
            println(envelope)
        }
    }

    audio.drain()
    audio.close()
}

fun parseArguments(args: MutableList<String>) {
    while (args.isNotEmpty()) {
        when (val arg = args.removeFirst()) {
            "-adsr" -> adsr = parseNextArgument(arg, args)
            "-end" -> endOverride = parseNextArgument(arg, args)
            "-gain" -> gain = parseNextArgument(arg, args)
            "-in" -> inputFile = parseNextArgument(arg, args)
            "-loop" -> loopPoint = parseNextArgument(arg, args)
            "-offset" -> offset = parseNextArgument(arg, args)
            "-out" -> outputFile = parseNextArgument(arg, args)
            "-play" -> play = true
            "-print" -> print = true
            "-sampleRate" -> sampleRate = parseNextArgument(arg, args)
            else -> error("Unsupported argument: $arg")
        }
    }
}

inline fun <reified T> parseNextArgument(arg: String, args: MutableList<String>): T {
    val nextArg = args.removeFirstOrNull() ?: error("Expected another argument after $arg")

    val parsedArg =
        when (T::class) {
            Int::class -> if (nextArg.startsWith("0x")) nextArg.drop(2).toIntOrNull(16) else nextArg.toIntOrNull()
            String::class -> nextArg
            else -> error("Unsupported argument type: ${T::class}")
        }

    if (parsedArg == null) {
        error("Expected a ${T::class} after $arg but found '$nextArg'")
    }

    return parsedArg as T
}

/**
 * Prints the compressed BRR blocks and decompressed samples, for debugging.
 */
fun printSamples() {
    for ((index, brrBlock) in brrBlocks.withIndex()) {
        print("$index:")
        if (brrBlock.end) { print(" END") }
        if (brrBlock.loop) { print(" LOOP") }
        println(" range ${brrBlock.range}, filter ${brrBlock.filter} ${brrBlock.nibbles}")

        print("    ")

        for (sampleIndex in (index * 16) until ((index + 1) * 16)) {
            print(" " + samples[sampleIndex])
        }

        println()
    }
}

fun printUsage() {
    println(
        """
        Usage:
        
        brr.main.kts -in <input ROM file> -offset <hex offset of sample> [options] [actions]

        options
        
            -adsr <two ADSR bytes>
            
                Sets the ADSR envelope to the given value. Takes precedence over Gain. Bit order follows:
                
                EDDDAAAA LLLRRRRR
                
                E: Enabled (ignored)
                D: Decay rate
                A: Attack rate
                L: Sustain level
                R: Sustain rate

            -end <end block index>
            
                Ends the sample immediately before the given BRR block index.
                
            -gain <one Gain byte>
            
                Sets the Gain envelope to the given value. Bit order follows:
                
                0DDDDDDD
                
                or
                
                1MMRRRRR
                
                D: Direct gain
                M: Mode
                V: Rate
            
            -loop <loop block index>
            
                Sets the loop point to the given BRR block index.
        
            -sampleRate $sampleRate
            
                Adjusts the output sample rate (changes speed and pitch).

        actions

            -out <output filename>

                Outputs the decompressed sample data as a WAV file with the given name.
            
            -play
                            
                Plays the decompressed audio aloud, respecting the end override, loop point, and ADSR envelope, if set.

            -print

                Prints the compressed and decompressed sample data to the terminal.
        """.trimIndent()
    )
}

parseArguments(args.toMutableList())

if (inputFile == "" || offset == 0) {
    printUsage()
} else {
    if (outputFile != null) {
        outputSamples(outputFile!!)
    }

    if (print) {
        printSamples()
    }

    if (play) {
        playAudio()
    }
}
