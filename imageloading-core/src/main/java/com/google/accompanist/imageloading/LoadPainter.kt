/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("DEPRECATION")

package com.google.accompanist.imageloading

import android.annotation.SuppressLint
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A generic 'loader' interface, allowing apps to implement the loading/fetching of a resource
 * as required.
 *
 * Ideally this interface would be a functional interface, but Kotlin doesn't currently support
 * suspending functional interfaces.
 *
 * @param R The data or input parameter type.
 */
@Stable
@Deprecated("Accompanist-ImageLoading is now deprecated. Consider using Coil: https://coil-kt.github.io/coil/compose")
fun interface Loader<R> {
    /**
     * Execute the 'load' with the given parameters.
     *
     * @param request The request of type [R] to use when executing.
     * @param size The size of the canvas, which allows loaders to load an optimally sized result.
     * @return The resulting [ImageLoadState].
     */
    fun load(request: R, size: IntSize): Flow<ImageLoadState>
}

/**
 * Object which holds default values for [rememberLoadPainter].
 */
@Deprecated("Accompanist-ImageLoading is now deprecated. Consider using Coil: https://coil-kt.github.io/coil/compose")
object LoadPainterDefaults {
    /**
     * Default duration in milliseconds for the fade-in animation.
     */
    @Deprecated("Accompanist-ImageLoading is now deprecated. Consider using Coil: https://coil-kt.github.io/coil/compose")
    const val FadeInTransitionDuration: Int = 1000
}

/**
 * A generic image loading painter, which provides the [Loader] interface for image loading
 * libraries to implement. Apps shouldn't generally use this function, instead preferring one
 * of the extension libraries which build upon this, such as the Coil and Glide libraries.
 *
 * @param loader The [Loader] to use to fetch [request].
 * @param request Updated value for [LoadPainter.request].
 * @param shouldRefetchOnSizeChange Updated value for [LoadPainter.shouldRefetchOnSizeChange].
 * @param fadeIn Whether to run a fade-in animation when images are successfully loaded.
 * Default: `false`.
 * @param fadeInDurationMs Duration for the fade animation in milliseconds when [fadeIn] is enabled.
 * @param previewPlaceholder Drawable resource ID which will be displayed when this function is
 * ran in preview mode.
 */
@Composable
@Deprecated("Accompanist-ImageLoading is now deprecated. Consider using Coil: https://coil-kt.github.io/coil/compose")
fun <R> rememberLoadPainter(
    loader: Loader<R>,
    request: R?,
    shouldRefetchOnSizeChange: ShouldRefetchOnSizeChange,
    fadeIn: Boolean = false,
    fadeInDurationMs: Int = LoadPainterDefaults.FadeInTransitionDuration,
    @DrawableRes previewPlaceholder: Int = 0,
): LoadPainter<R> {
    val coroutineScope = rememberCoroutineScope()

    // Our LoadPainter. This invokes the loader as appropriate to display the result.
    val painter = remember(loader, coroutineScope) {
        LoadPainter(loader, coroutineScope)
    }
    painter.request = request
    painter.shouldRefetchOnSizeChange = shouldRefetchOnSizeChange
    painter.rootViewSize = LocalView.current.let { IntSize(it.width, it.height) }

    // This runs our fade in animation
    animateFadeInColorFilter(
        painter = painter,
        enabled = { result ->
            // We run the fade in animation if the result is loaded from disk/network.
            // This allows us to approximate only running the animation on 'first load'
            fadeIn && result is ImageLoadState.Success && result.source != DataSource.MEMORY
        },
        durationMs = fadeInDurationMs,
    )

    // Our result painter, created from the ImageState with some composition lifecycle
    // callbacks
    updatePainter(painter, previewPlaceholder)

    return painter
}

/**
 * Interface that allows apps to control whether a request is re-run once the size changes.
 */
@Deprecated("Accompanist-ImageLoading is now deprecated. Consider using Coil: https://coil-kt.github.io/coil/compose")
fun interface ShouldRefetchOnSizeChange {
    /**
     * Return `true` if the request should be re-run if the [size] has changed.
     *
     * @param currentState The current request state.
     * @param size The new size.
     */
    operator fun invoke(currentState: ImageLoadState, size: IntSize): Boolean
}

/**
 * A generic image loading painter, which provides the [Loader] interface for image loading
 * libraries to implement. Apps shouldn't generally use this function, instead preferring one
 * of the extension libraries which build upon this, such as the Coil and Glide libraries.
 *
 * Instances can be created and remembered via the [rememberLoadPainter] function.
 */
@Deprecated("Accompanist-ImageLoading is now deprecated. Consider using Coil: https://coil-kt.github.io/coil/compose")
class LoadPainter<R> internal constructor(
    private val loader: Loader<R>,
    private val coroutineScope: CoroutineScope,
) : Painter(), RememberObserver {
    private val paint by lazy(LazyThreadSafetyMode.NONE) { Paint() }

    internal var painter by mutableStateOf<Painter>(EmptyPainter)
    internal var transitionColorFilter by mutableStateOf<ColorFilter?>(null)

    // CoroutineScope for the current request
    private var requestCoroutineScope: CoroutineScope? = null

    /**
     * The current request object.
     */
    var request by mutableStateOf<R?>(null)

    /**
     * The root view size.
     */
    internal var rootViewSize by mutableStateOf(IntSize(0, 0))

    /**
     * Lambda which will be invoked when the size changes, allowing
     * optional re-fetching of the image.
     */
    var shouldRefetchOnSizeChange by mutableStateOf(ShouldRefetchOnSizeChange { _, _ -> false })

    /**
     * The current [ImageLoadState].
     */
    var loadState: ImageLoadState by mutableStateOf(ImageLoadState.Empty)
        private set

    private var alpha: Float by mutableStateOf(1f)
    private var colorFilter: ColorFilter? by mutableStateOf(null)

    /**
     * Our size to use when performing the image load request.
     */
    private var requestSize by mutableStateOf<IntSize?>(null)

    override val intrinsicSize: Size
        get() = painter.intrinsicSize

    override fun applyAlpha(alpha: Float): Boolean {
        this.alpha = alpha
        return true
    }

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        this.colorFilter = colorFilter
        return true
    }

    override fun DrawScope.onDraw() {
        // Update the request size, based on the provided canvas size
        updateRequestSize(canvasSize = size)

        val transitionColorFilter = transitionColorFilter
        if (colorFilter != null && transitionColorFilter != null) {
            // If we have a transition color filter, and a specified color filter we need to
            // draw the content in a layer for both to apply.
            // See https://github.com/google/accompanist/issues/262
            drawIntoCanvas { canvas ->
                paint.colorFilter = transitionColorFilter
                canvas.saveLayer(bounds = size.toRect(), paint = paint)
                with(painter) {
                    draw(size, alpha, colorFilter)
                }
                canvas.restore()
            }
        } else {
            // Otherwise we just draw the content directly, using the filter
            with(painter) {
                draw(size, alpha, colorFilter ?: transitionColorFilter)
            }
        }
    }

    override fun onAbandoned() {
        // We've been abandoned from composition, so cancel our request scope
        requestCoroutineScope?.cancel()
        requestCoroutineScope = null
    }

    override fun onForgotten() {
        // We've been forgotten from composition, so cancel our request scope
        requestCoroutineScope?.cancel()
        requestCoroutineScope = null
    }

    override fun onRemembered() {
        // Cancel any on-going scope (this shouldn't really happen anyway)
        requestCoroutineScope?.cancel()

        // Create a new CoroutineScope for the current request. We use the provided
        // `coroutineScope` as the source for everything, but we create a new child Job.
        // This allows us cancel the scope without affecting the parent scope's job.
        val scope = coroutineScope.coroutineContext.let { context ->
            CoroutineScope(context + Job(context[Job]))
        }.also { requestCoroutineScope = it }

        // We've been remembered, so launch a coroutine to observe the current request object,
        // and the request size. Whenever either of these values change, the collectLatest block
        // will run and execute the image load (with any on-going request cancelled).
        scope.launch {
            combine(
                snapshotFlow { request },
                snapshotFlow { requestSize },
                transform = { request, size -> request to size }
            ).collectLatest { (request, size) ->
                execute(request, size)
            }
        }

        // Our fail-safe. If we don't receive an appropriate size from `onDraw()` we update the
        // request size to be -1, -1 which will load the original size image.
        // The ideal fix for this is waiting on https://issuetracker.google.com/186012457
        scope.launch {
            if (requestSize == null) {
                delay(32) // 32ms should be enough time for measure/layout/draw to happen.

                if (requestSize == null) {
                    // If we still don't have a request size, resolve the size without
                    // the canvas size
                    updateRequestSize(canvasSize = Size.Zero)
                }
            }
        }
    }

    /**
     * The function which executes the requests, and update [loadState] as appropriate with the
     * result.
     */
    private suspend fun execute(request: R?, size: IntSize?) {
        if (request == null || size == null) {
            // If we don't have a request, set our state to Empty and return
            loadState = ImageLoadState.Empty
            return
        }

        if (loadState != ImageLoadState.Empty &&
            request == loadState.request &&
            !shouldRefetchOnSizeChange(loadState, size)
        ) {
            // If we're not empty, the request is the same and shouldRefetchOnSizeChange()
            // returns false, return now to skip this request
            return
        }

        loader.load(request, size)
            .catch { throwable ->
                when (throwable) {
                    // Re-throw all Errors, IllegalStateExceptions & IllegalArgumentExceptions
                    is Error -> throw throwable
                    is IllegalStateException -> throw throwable
                    is IllegalArgumentException -> throw throwable
                    else -> {
                        // Anything else, we wrap in a Error state instance and re-emit
                        emit(
                            ImageLoadState.Error(
                                result = null,
                                throwable = throwable,
                                request = request
                            )
                        )
                    }
                }
            }
            .collect { loadState = it }
    }

    private fun updateRequestSize(canvasSize: Size) {
        requestSize = IntSize(
            width = when {
                // If we have a canvas width, use it...
                canvasSize.width >= 0.5f -> canvasSize.width.roundToInt()
                // Otherwise we fall-back to the root view size as an upper bound
                rootViewSize.width > 0 -> rootViewSize.width
                else -> -1
            },
            height = when {
                // If we have a canvas height, use it...
                canvasSize.height >= 0.5f -> canvasSize.height.roundToInt()
                // Otherwise we fall-back to the root view size as an upper bound
                rootViewSize.height > 0 -> rootViewSize.height
                else -> -1
            },
        )
    }
}

/**
 * Allows us observe the current result [Painter] as state. This function allows us to
 * minimize the amount of composition needed, such that only this function needs to be restarted
 * when the `loadState` changes.
 */
@SuppressLint("ComposableNaming")
@Composable
private fun <R> updatePainter(
    loadPainter: LoadPainter<R>,
    @DrawableRes previewPlaceholder: Int = 0,
) {
    loadPainter.painter = if (LocalInspectionMode.current && previewPlaceholder != 0) {
        // If we're in inspection mode (preview) and we have a preview placeholder, just draw
        // that using an Image and return
        painterResource(previewPlaceholder)
    } else {
        // This may look like a useless remember, but this allows any Painters instances
        // to receive remember events (if it implements RememberObserver). Do not remove.
        remember(loadPainter.loadState) { loadPainter.loadState.painter } ?: EmptyPainter
    }
}

@SuppressLint("ComposableNaming")
@Composable
private fun <R> animateFadeInColorFilter(
    painter: LoadPainter<R>,
    enabled: (ImageLoadState) -> Boolean,
    durationMs: Int,
) {
    val state = painter.loadState
    painter.transitionColorFilter = if (enabled(state)) {
        val colorMatrix = remember { ColorMatrix() }
        val fadeInTransition = updateFadeInTransition(state, durationMs)

        if (!fadeInTransition.isFinished) {
            colorMatrix.apply {
                updateAlpha(fadeInTransition.alpha)
                updateBrightness(fadeInTransition.brightness)
                updateSaturation(fadeInTransition.saturation)
            }.let { ColorFilter.colorMatrix(it) }
        } else {
            // If the fade-in isn't running, reset the color matrix
            null
        }
    } else null // If the fade in is not enabled, we don't use a fade in transition
}

internal object EmptyPainter : Painter() {
    override val intrinsicSize: Size get() = Size.Unspecified
    override fun DrawScope.onDraw() {}
}
