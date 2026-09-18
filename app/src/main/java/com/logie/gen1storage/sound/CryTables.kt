package com.logie.gen1storage.sound

/**
 * How the generated cry tables are stored, and the only thing that reads them
 * back.
 *
 * They are strings rather than array literals because they do not fit as
 * literals: every element of an `intArrayOf` inside an object is instructions
 * in that object's static initialiser, and a few thousand notes overflow the
 * 64 KB a single method is allowed to be. A string constant is one instruction
 * whatever its length, so each channel program is one, decoded the first time
 * that cry is played.
 *
 * Each value is one character, carried up by [BIAS] so that the negative ones
 * — a rising volume envelope is a negative fade — are still positive, and so
 * that nothing lands in the surrogate range, which a string constant cannot
 * hold.
 */
internal object CryTables {

    private const val BIAS = 2048

    fun decode(program: String): IntArray =
        IntArray(program.length) { program[it].code - BIAS }

    fun valueAt(packed: String, at: Int): Int = packed[at].code - BIAS
}
