package com.logie.gen1storage.mods

/**
 * Whether one file out of a mod zip is this mod's own, or somebody else's.
 *
 * Two questions, asked of everything in turn: is this a known console dump
 * or proprietary container by its header or its extension (see
 * [ModBinaryRules]), and if it is a picture, is it too close to a sprite
 * pret's own decompilations already draw (see [DHash] and
 * [ModReferenceDatabase]). Either one answers "no" on its own — a mod does
 * not get to keep a ROM because its sprites are original, and it does not
 * get to keep a ripped sprite because the rest of the zip is clean.
 *
 * Deliberately free of anything that opens a zip or decodes a bitmap: see
 * `ModImportPipeline` for the Android side that reads a picked file and
 * calls this once per entry.
 */
object ModAssetScanner {

    /** Enough of a file to find a header signature in; nothing here reads further than this. */
    const val HEADER_CHUNK_BYTES = 65536

    private val IMAGE_EXTENSIONS = setOf(".png", ".bmp", ".jpg", ".jpeg", ".webp")

    fun isImage(filename: String): Boolean = ModBinaryRules.extensionOf(filename) in IMAGE_EXTENSIONS

    /** The binary/extension/signature checks alone — see [ModBinaryRules.checkHeader]. */
    fun checkBinary(filename: String, headerChunk: ByteArray): ModViolation? =
        ModBinaryRules.checkHeader(filename, headerChunk)

    /**
     * One image, already resized to the hash's own grid and reduced to
     * luminance (see [DHash.fromResizedLuminance]), against the reference
     * database. Null for a blank swatch — a single flat colour has nothing
     * in it that could match anything — and for anything that reads as
     * this mod's own artwork.
     */
    fun checkImage(
        filename: String,
        resizedLuminance: IntArray,
        referenceDb: ModReferenceDatabase,
    ): ModViolation? {
        if (resizedLuminance.isEmpty()) return null
        val first = resizedLuminance[0]
        if (resizedLuminance.all { it == first }) return null

        val hash = DHash.fromResizedLuminance(resizedLuminance)
        val (matchedKey, distance) = referenceDb.closestMatch(hash) ?: return null
        return when (DHash.verdictFor(distance)) {
            DHash.Verdict.CLEAN -> null
            DHash.Verdict.FLAGGED -> ModViolation(
                filename,
                "Close to a canonical sprite",
                "'$filename' is similar enough to '$matchedKey' (distance $distance/256) to need a human look before it can be trusted as original art.",
            )
            DHash.Verdict.REJECT -> ModViolation(
                filename,
                "Matches a canonical sprite",
                "'$filename' matches '$matchedKey' too closely (distance $distance/256) to be anything but that sprite.",
            )
        }
    }
}
