package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioFormat

internal fun audioRendererFixture(format: AudioFormat, bufferMillis: Int, report: (String) -> Unit = {}): Any {
    val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
    return type.declaredConstructors.single { !it.isSynthetic }.apply { isAccessible = true }
        .newInstance(format, false, false, 0, 0, AudioFocusCoordinator(null, false, false),
            0, bufferMillis, report, null, null, null, AudioOutputRoutes(), emptyMap<Int, Int>(), false,
            { _: Any -> }, false, false, null, false)
}
