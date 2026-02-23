package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

sealed interface VoiceCommand {
    data class Track(val name: String) : VoiceCommand

    data class SaveLandmark(val name: String) : VoiceCommand

    data class Forget(val name: String) : VoiceCommand

    data class Ping(val target: String) : VoiceCommand

    data class Only(val className: String) : VoiceCommand

    data object EnableNorthCueMode : VoiceCommand

    data object DisableNorthCueMode : VoiceCommand

    data object ActivateObjectSonification : VoiceCommand

    data object DeactivateObjectSonification : VoiceCommand

    data class Unknown(val raw: String) : VoiceCommand
}
