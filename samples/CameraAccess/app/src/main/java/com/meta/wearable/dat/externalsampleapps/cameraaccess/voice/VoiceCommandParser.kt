package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

class VoiceCommandParser {
    fun parse(transcript: String): VoiceCommand {
        val cleaned = transcript.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) {
            return VoiceCommand.Unknown(transcript)
        }

        val lower = cleaned.lowercase()
        if (lower.startsWith("only ")) {
            val className = cleaned.substringAfter(" ", "").trim()
            return if (className.isNotEmpty()) VoiceCommand.Only(className) else VoiceCommand.Unknown(transcript)
        }
        if (lower.endsWith(" only")) {
            val className = cleaned.substringBeforeLast(" only").trim()
            return if (className.isNotEmpty()) VoiceCommand.Only(className) else VoiceCommand.Unknown(transcript)
        }
        if (lower.startsWith("ping ")) {
            val target = cleaned.substringAfter(" ", "").trim()
            return if (target.isNotEmpty()) VoiceCommand.Ping(target) else VoiceCommand.Unknown(transcript)
        }
        if (lower == "north mode on" || lower == "enable north mode") {
            return VoiceCommand.EnableNorthCueMode
        }
        if (lower == "north mode off" || lower == "disable north mode") {
            return VoiceCommand.DisableNorthCueMode
        }
        if (lower == "activate") {
            return VoiceCommand.ActivateObjectSonification
        }
        if (lower == "deactivate") {
            return VoiceCommand.DeactivateObjectSonification
        }

        if (lower.startsWith("forget ")) {
            val name = cleaned.substringAfter(" ", "").trim()
            return if (name.isNotEmpty()) VoiceCommand.Forget(name) else VoiceCommand.Unknown(transcript)
        }

        if (lower.startsWith("drop ")) {
            val name = cleaned.substringAfter(" ", "").trim()
            return if (name.isNotEmpty()) VoiceCommand.Forget(name) else VoiceCommand.Unknown(transcript)
        }

        if (lower.startsWith("track ")) {
            val name = cleaned.substringAfter(" ", "").trim()
            return if (name.isNotEmpty()) VoiceCommand.Track(name) else VoiceCommand.Unknown(transcript)
        }
        if (lower.startsWith("save landmark ")) {
            val name = cleaned.substringAfter(" ", "").substringAfter(" ", "").trim()
            return if (name.isNotEmpty()) VoiceCommand.SaveLandmark(name) else VoiceCommand.Unknown(transcript)
        }
        if (lower.startsWith("save ")) {
            val name = cleaned.substringAfter(" ", "").trim()
            return if (name.isNotEmpty()) VoiceCommand.SaveLandmark(name) else VoiceCommand.Unknown(transcript)
        }

        return VoiceCommand.Unknown(transcript)
    }
}
