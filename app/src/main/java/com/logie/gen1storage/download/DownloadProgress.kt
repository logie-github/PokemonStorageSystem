package com.logie.gen1storage.download

/** How far a bulk download has got. Shared by the sprites and the cries. */
data class DownloadProgress(
    val done: Int,
    val total: Int,
    val failed: Int = 0,
    val finished: Boolean = false,
    val error: String? = null,
) {
    val percent: Int get() = if (total <= 0) 0 else (done * 100) / total
}
