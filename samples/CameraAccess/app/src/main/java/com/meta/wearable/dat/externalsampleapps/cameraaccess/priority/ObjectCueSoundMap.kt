package com.meta.wearable.dat.externalsampleapps.cameraaccess.priority

object ObjectCueSoundMap {
    private const val DEFAULT_SOUND = "audio/unlock.wav"

    fun soundForLabel(label: String): String {
        return when (normalizeLabel(label)) {
            "person" -> "audio/unlock.wav"
            "chair" -> "audio/shortz.wav"
            "door" -> "audio/punch.wav"
            "table" -> "audio/retro.wav"
            "cup" -> "audio/bling.wav"
            "phone" -> "audio/scifi.wav"
            else -> DEFAULT_SOUND
        }
    }

    fun normalizeLabel(label: String): String {
        return when (label.trim().lowercase()) {
            "person", "people", "persons" -> "person"
            "chair", "chairs" -> "chair"
            "door", "doors" -> "door"
            "table", "tables", "dining table", "dining tables" -> "table"
            "cup", "cups" -> "cup"
            "phone", "phones", "cell phone", "cell phones", "mobile phone", "mobile phones" -> "phone"
            else -> label.trim().lowercase()
        }
    }
}
