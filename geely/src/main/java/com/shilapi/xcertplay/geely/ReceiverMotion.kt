package com.shilapi.xcertplay.geely

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

internal object ReceiverMotion {
    const val pageMs=260
    const val colorMs=200
    const val expandMs=240
    val ease=CubicBezierEasing(.22f,.8f,.2f,1f)
}

/** Animate only the incoming app page: never retain two live forms or a video Surface. */
@Composable internal fun ReceiverPageMotion(page:Int,modifier:Modifier,content:@Composable BoxScope.()->Unit) {
    val progress=remember(page) {Animatable(0f)}
    LaunchedEffect(progress) {progress.animateTo(1f,tween(ReceiverMotion.pageMs,easing=ReceiverMotion.ease))}
    Box(modifier.graphicsLayer {
        alpha=.85f+.15f*progress.value
        translationY=(1f-progress.value)*12.dp.toPx()
    },content=content)
}

/** Layer-only press response; no measurement or callback is animated or delayed. */
@Composable internal fun Modifier.receiverPressMotion(source:MutableInteractionSource):Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale=animateFloatAsState(if(pressed) .985f else 1f,
        spring(dampingRatio=.85f,stiffness=550f),label="primary press")
    return graphicsLayer {scaleX=scale.value;scaleY=scale.value}
}
