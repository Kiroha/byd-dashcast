package com.byd.dashcast.media

/** Ephemeral metadata only; never persist, log, or attach it to navigation frames. */
data class ClusterMediaData(val source: Any, val title: String, val artist: String, val playing: Boolean)

object ClusterMediaEncoding {
    const val MAX_BYTES = 254 // OEM accepts <=255; UTF-16 must have an even length.

    fun text(title: String, artist: String): ByteArray {
        val cleanTitle = sanitize(title)
        val cleanArtist = sanitize(artist)
        val content = if (cleanArtist.isEmpty()) cleanTitle else "$cleanArtist - $cleanTitle"
        val bounded = StringBuilder()
        // Reserve four spaces. Do not split surrogate pairs or emit malformed UTF-16.
        var offset = 0
        while (offset < content.length) {
            val cp = content.codePointAt(offset)
            val count = Character.charCount(cp)
            if ((bounded.length + count + 4) * 2 > MAX_BYTES) break
            bounded.appendCodePoint(cp)
            offset += count
        }
        return ("  $bounded  ").toByteArray(Charsets.UTF_16LE)
    }

    fun sanitize(value: String): String {
        val result = StringBuilder()
        var offset = 0
        // Bound work as well as the wire payload; metadata can be supplied by any media app.
        while (offset < value.length && result.length < 256) {
            val cp = value.codePointAt(offset)
            offset += Character.charCount(cp)
            when {
                cp in 0xD800..0xDFFF -> result.append('\uFFFD')
                Character.isISOControl(cp) -> result.append(' ')
                else -> result.appendCodePoint(cp)
            }
        }
        return result.toString().trim()
    }
}

enum class MediaPlayback { PLAYING, PAUSED, OTHER }

data class MediaCandidate(val source: Any, val title: String, val artist: String, val playback: MediaPlayback)

/** Prefer a playing session, keep its identity across list reordering, retain only its own pause. */
object MediaSourceSelector {
    fun select(candidates: List<MediaCandidate>, previous: Any?): ClusterMediaData? {
        val playing = candidates.filter { it.playback == MediaPlayback.PLAYING &&
            ClusterMediaEncoding.sanitize(it.title).isNotEmpty() }
        val chosen = playing.firstOrNull { it.source == previous } ?: playing.firstOrNull()
            ?: candidates.firstOrNull { it.source == previous && it.playback == MediaPlayback.PAUSED &&
                ClusterMediaEncoding.sanitize(it.title).isNotEmpty() }
            ?: return null
        return ClusterMediaData(chosen.source, ClusterMediaEncoding.sanitize(chosen.title),
            ClusterMediaEncoding.sanitize(chosen.artist), chosen.playback == MediaPlayback.PLAYING)
    }
}
