package com.meta.wearable.dat.externalsampleapps.cameraaccess.spatial

fun normalize360(degrees: Float): Float {
    var d = degrees % 360f
    if (d < 0f) d += 360f
    return d
}

fun normalizeSigned180(degrees: Float): Float {
    var d = normalize360(degrees)
    if (d > 180f) d -= 360f
    return d
}

