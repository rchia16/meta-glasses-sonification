package com.meta.wearable.dat.externalsampleapps.cameraaccess.scene

enum class SceneClass(
    val label: String,
) {
    WALL("wall"),
    FLOOR("floor"),
    CEILING("ceiling"),
    DOOR("door"),
    WINDOWPANE("windowpane"),
    TABLE("table"),
    CHAIR("chair"),
    SOFA("sofa"),
    ;

    companion object {
        val allLabels: Set<String> = values().mapTo(linkedSetOf()) { it.label }

        fun fromLabel(label: String): SceneClass? {
            val normalized = label.trim().lowercase()
            return values().firstOrNull { it.label == normalized }
        }
    }
}

data class SceneSonificationConfig(
    val isEnabled: Boolean = false,
    val enabledClasses: Set<SceneClass> = SceneClass.values().toSet(),
) {
    fun isClassEnabled(label: String): Boolean {
        val sceneClass = SceneClass.fromLabel(label) ?: return false
        return enabledClasses.contains(sceneClass)
    }
}
