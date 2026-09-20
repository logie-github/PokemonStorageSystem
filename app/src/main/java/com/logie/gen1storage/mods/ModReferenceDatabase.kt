package com.logie.gen1storage.mods

import org.json.JSONObject

/**
 * Every hash `tools/generate_mod_scanner_hashes.py` computed off pret's own
 * decompilations, kept as numbers rather than as the pictures they came
 * from.
 *
 * A hash cannot be turned back into the sprite it was computed from, so
 * this — and the `mod_reference_hashes.json` asset it is built from — is
 * the one place this app gets to compare against the cartridge's own art
 * without ever holding a copy of it.
 */
class ModReferenceDatabase(private val hashes: Map<String, LongArray>) {

    val size: Int get() = hashes.size

    /**
     * The closest reference sprite to [hash], and how far apart they are —
     * null only when this database is empty. Every entry is checked: with
     * a couple of thousand reference sprites and a scan that runs once per
     * imported mod rather than once per frame, a linear scan costs nothing
     * worth optimising away.
     */
    fun closestMatch(hash: LongArray): Pair<String, Int>? {
        var bestKey: String? = null
        var bestDistance = Int.MAX_VALUE
        for ((key, reference) in hashes) {
            val distance = DHash.hammingDistance(hash, reference)
            if (distance < bestDistance) {
                bestDistance = distance
                bestKey = key
                if (distance == 0) break
            }
        }
        return bestKey?.let { it to bestDistance }
    }

    companion object {
        /** An empty database. Every image passes it, which is the safe failure when the asset can't be read. */
        val EMPTY = ModReferenceDatabase(emptyMap())

        fun parse(json: String): ModReferenceDatabase {
            val root = JSONObject(json)
            val hashesJson = root.getJSONObject("hashes")
            val hashes = LinkedHashMap<String, LongArray>(hashesJson.length())
            val keys = hashesJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val hash = DHash.fromHex(hashesJson.getString(key)) ?: continue
                hashes[key] = hash
            }
            return ModReferenceDatabase(hashes)
        }
    }
}
