package com.meta.wearable.dat.externalsampleapps.cameraaccess.audio

data class ObjectSonificationProfile(
    val gain: Float,
    val playbackRateScale: Float,
    val tiltEq: Float,
)

object ObjectSonificationProfiles {
    private val fallbackProfile =
        ObjectSonificationProfile(
            gain = 1.0f,
            playbackRateScale = 1.0f,
            tiltEq = 0f,
        )

    private val defaultsByLabel =
        linkedMapOf(
            "person" to ObjectSonificationProfile(gain = 1.0f, playbackRateScale = 0.98f, tiltEq = -0.10f),
            "chair" to ObjectSonificationProfile(gain = 0.9f, playbackRateScale = 1.10f, tiltEq = 0.18f),
            "door" to ObjectSonificationProfile(gain = 1.05f, playbackRateScale = 0.92f, tiltEq = -0.18f),
            "table" to ObjectSonificationProfile(gain = 0.95f, playbackRateScale = 0.96f, tiltEq = -0.06f),
            "cup" to ObjectSonificationProfile(gain = 0.82f, playbackRateScale = 1.18f, tiltEq = 0.24f),
            "phone" to ObjectSonificationProfile(gain = 0.9f, playbackRateScale = 1.22f, tiltEq = 0.28f),
        )
    private val overridesByLabel = mutableMapOf<String, ObjectSonificationProfile>()

    @Synchronized
    fun supportedLabels(): List<String> = defaultsByLabel.keys.toList()

    @Synchronized
    fun forLabel(label: String?): ObjectSonificationProfile {
        val normalized = normalize(label)
        return overridesByLabel[normalized] ?: defaultsByLabel[normalized] ?: fallbackProfile
    }

    @Synchronized
    fun setProfileForLabel(label: String, profile: ObjectSonificationProfile) {
        val normalized = normalize(label)
        if (normalized !in defaultsByLabel.keys) return
        overridesByLabel[normalized] = profile
    }

    @Synchronized
    fun resetAll() {
        overridesByLabel.clear()
    }

    @Synchronized
    fun snapshotProfiles(): Map<String, ObjectSonificationProfile> {
        return defaultsByLabel.keys.associateWith { label -> forLabel(label) }
    }

    private fun normalize(label: String?): String {
        return when (label?.trim()?.lowercase()) {
            "dining table" -> "table"
            "cell phone", "mobile phone" -> "phone"
            null -> ""
            else -> label.trim().lowercase()
        }
    }
}
