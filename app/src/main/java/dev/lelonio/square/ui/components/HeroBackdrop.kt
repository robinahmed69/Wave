package dev.lelonio.square.ui.components

import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.SuccessResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest

/**
 * A picture that stops being a picture on its way down the screen.
 * (Original long comments shortened; code is unchanged except for `showSharp`.)
 */
@Composable
fun HeroBackdrop(
    artworkUrl: String?,
    title: String,
    pageColor: Color,
    modifier: Modifier = Modifier,
    motionUrl: String? = null,
    motionPlaying: Boolean = true,
    pending: Boolean = false,
    softenFrom: Float = 0.42f,
    softenTo: Float = 0.72f,
    fadeToPage: Boolean = true,
    imageAspect: Float? = null,
    extendPicture: Boolean = false,
    zoom: Float = 1f,
    /** NEW: false হলে বড় কভার ও মুভিং কভার আঁকা হয় না; শুধু ঝাপসা ব্যাকগ্রাউন্ড থাকে। */
    showSharp: Boolean = true,
) {
    val scrim = dev.lelonio.square.ui.theme.scrimColor()
    val motion = remember(motionUrl) { MotionFrames() }
    val frameFade = remember(motion) { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(motion) {
        androidx.compose.runtime.snapshotFlow { motion.latest.value }.collectLatest { frame ->
            if (frame == null) return@collectLatest
            frameFade.snapTo(0f)
            frameFade.animateTo(
                1f,
                tween(MOTION_FRAME_FADE_MS, easing = androidx.compose.animation.core.LinearEasing),
            )
        }
    }
    val context = LocalContext.current
    val extended = extendPicture && !fadeToPage
    val art by produceState<ArtSet?>(null, artworkUrl, zoom, extended) {
        if (artworkUrl == null) {
            value = null
            return@produceState
        }
        value = coroutineScope {
            val cover = async {
                val large = spotifyLargeUrl(artworkUrl)
                val sharper = large != artworkUrl && !dev.lelonio.square.playback.OfflineMode.active.value
                (if (sharper) loadArt(context, large, COVER_PX) else null)
                    ?: loadArt(context, artworkUrl, COVER_PX)
            }
            val edge = async { if (extended) loadArt(context, artworkUrl, EXTENSION_PX) else null }
            val blur = async { loadArt(context, artworkUrl, HERO_BLUR_PX, blurred = true) }
            cover.await()?.let { ArtSet(it, edge.await(), blur.await(), zoom) }
        }
    }
    Box(modifier) {
        if (extended && artworkUrl != null) {
            PictureExtension(
                art = art,
                motion = motion,
                imageAspect = imageAspect,
                softenFrom = softenFrom,
                softenTo = softenTo,
                fade = { frameFade.value },
            )
        }
        if (imageAspect != null && fadeToPage) {
            Box(Modifier.fillMaxSize().background(pageColor))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .then(
                    if (imageAspect != null) {
                        Modifier.pictureSlot(imageAspect)
                    } else {
                        Modifier.fillMaxSize()
                    },
                )
                .align(Alignment.TopCenter)
                .then(
                    if (fadeToPage) {
                        Modifier
                    } else {
                        Modifier
                            .graphicsLayer {
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        *if (extendPicture) {
                                            fading(1f - SLOT_FADE, 1f)
                                        } else {
                                            fading(softenFrom, softenTo)
                                        },
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                    },
                ),
        ) {
        // CHANGED: showSharp শর্ত যোগ হয়েছে
        if (showSharp) CoverFill(art = art, modifier = Modifier.fillMaxSize())

        // CHANGED: showSharp শর্ত যোগ হয়েছে
        if (showSharp && motionUrl != null) {
            MotionCover(
                url = motionUrl,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    },
                onFrame = motion::take,
                playing = motionPlaying,
            )
        }

        if (artworkUrl != null) {
            Crossfade(
                targetState = art,
                animationSpec = tween(SWAP_MS),
                label = "coverBlur",
                modifier = Modifier.fillMaxSize(),
            ) { shown ->
            val blur = shown?.blur ?: return@Crossfade
            val stops = if (fadeToPage) {
                softening(softenFrom, softenTo, Color.Black)
            } else if (extendPicture) {
                softening(
                    1f - SLOT_FADE - SLOT_BLUR_LEAD,
                    1f - SLOT_FADE + SLOT_BLUR_TAIL,
                    Color.Black,
                )
            } else {
                softening(
                    (softenFrom - 0.16f).coerceAtLeast(0f),
                    softenFrom + 0.03f,
                    Color.Black,
                )
            }
            val shift = remember { floatArrayOf(0f) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layout { measurable, constraints ->
                        val full = constraints.maxHeight
                        val top = ((full * stops.first().first) - SMOOTH_PX * 2).toInt().coerceIn(0, full)
                        shift[0] = top.toFloat()
                        val placeable = measurable.measure(
                            constraints.copy(minHeight = full - top, maxHeight = full - top),
                        )
                        layout(constraints.maxWidth, full) { placeable.place(0, top) }
                    }
                    .then(if (motionUrl != null) Modifier.smoothedUp() else Modifier)
                    .then(
                        if (motionUrl != null) {
                            Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        } else {
                            Modifier
                        },
                    )
                    .drawWithCache {
                        val frame = motion.latest.value
                        val before = motion.previous.value
                        val top = shift[0]
                        val whole = androidx.compose.ui.geometry.Size(size.width, size.height + top)
                        val brush = maskedPictureBrush(
                            frame?.soft ?: blur,
                            shown.zoom,
                            whole,
                            stops,
                        )
                        val earlier = before?.let { maskedPictureBrush(it.soft, shown.zoom, whole, stops) }
                        onDrawBehind {
                            translate(top = -top) {
                                val at = androidx.compose.ui.geometry.Offset(0f, top)
                                if (earlier == null) {
                                    drawRect(brush = brush, topLeft = at, size = size)
                                } else {
                                    val share = frameFade.value
                                    drawRect(brush = earlier, topLeft = at, size = size, alpha = 1f - share)
                                    drawRect(brush = brush, topLeft = at, size = size, alpha = share, blendMode = BlendMode.Plus)
                                }
                            }
                        }
                    },
            )
            }
        }

        if (fadeToPage) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to scrim.copy(alpha = 0.22f),
                        (softenFrom * 0.45f) to Color.Transparent,
                        *softening(
                            from = softenFrom + (softenTo - softenFrom) * COLOUR_LAG,
                            to = softenTo,
                            colour = pageColor,
                        ),
                        1f to pageColor,
                    ),
                ),
        )
        }
        }

        if (!fadeToPage) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        val ends = imageAspect
                            ?.let { (size.width / it / size.height).coerceIn(0f, 1f) }
                            ?: 1f
                        val stops = buildList {
                            add(0f to scrim.copy(alpha = 0.20f))
                            add((ends * 0.45f) to Color.Transparent)
                            add((ends * 0.92f) to scrim.copy(alpha = 0.05f))
                            if (ends < 0.999f) {
                                add(ends to scrim.copy(alpha = 0.12f))
                                add(1f to scrim.copy(alpha = 0.30f))
                            } else {
                                add(1f to scrim.copy(alpha = 0.12f))
                            }
                        }
                        drawRect(brush = Brush.verticalGradient(*stops.toTypedArray()))
                    },
            )
        }
    }
}

private fun softening(
    from: Float,
    to: Float,
    colour: Color,
): Array<Pair<Float, Color>> {
    val steps = 8
    return Array(steps + 1) { index ->
        val t = index.toFloat() / steps
        val at = from + (to - from) * t
        val eased = t * t * (3f - 2f * t)
        at to colour.copy(alpha = colour.alpha * eased)
    }
}

private const val COLOUR_LAG = 0.22f

private const val SWAP_MS = 450

private const val HERO_BLUR_PX = 240

private val HeroBlur = BlurTransformation(radius = 10, passes = 2)

@Composable
private fun CoverFill(art: ArtSet?, modifier: Modifier = Modifier) {
    Crossfade(
        targetState = art,
        animationSpec = tween(SWAP_MS),
        label = "cover",
        modifier = modifier,
    ) { shown ->
        if (shown == null) return@Crossfade
        Box(Modifier.fillMaxSize().drawBehind { drawCropped(shown.cover, shown.zoom) })
    }
}

private class ArtSet(
    val cover: ImageBitmap,
    val edge: ImageBitmap?,
    val blur: ImageBitmap?,
    val zoom: Float,
)

private suspend fun loadArt(
    context: android.content.Context,
    url: String,
    px: Int,
    blurred: Boolean = false,
): ImageBitmap? {
    val request = ImageRequest.Builder(context)
        .data(artSource(url))
        .size(px)
        .allowHardware(false)
        .apply { if (blurred) transformations(HeroBlur) }
        .build()
    val result = runCatching { context.imageLoader.execute(request) }.getOrNull()
    return (result as? SuccessResult)?.drawable?.toBitmap()?.asImageBitmap()
}

private const val COVER_PX = 1600

@Composable
private fun PictureExtension(
    art: ArtSet?,
    motion: MotionFrames,
    imageAspect: Float?,
    softenFrom: Float,
    softenTo: Float,
    fade: () -> Float,
) {
    Crossfade(
        targetState = art,
        animationSpec = tween(SWAP_MS),
        label = "pictureExtension",
        modifier = Modifier.fillMaxSize(),
    ) { shown ->
        val image = shown?.edge ?: return@Crossfade
        val moving by remember(motion) { androidx.compose.runtime.derivedStateOf { motion.latest.value != null } }
        if (moving) {
            ExtensionLayer(
                image = image,
                imageZoom = shown.zoom,
                imageAspect = imageAspect,
                softenFrom = softenFrom,
                softenTo = softenTo,
                moving = true,
                frame = { (motion.previous.value ?: motion.latest.value)?.sharp },
                opacity = { 1f },
                fades = false,
            )
        }
        ExtensionLayer(
            image = image,
            imageZoom = shown.zoom,
            imageAspect = imageAspect,
            softenFrom = softenFrom,
            softenTo = softenTo,
            moving = moving,
            frame = { motion.latest.value?.sharp },
            opacity = fade,
            fades = moving,
        )
    }
}

@Composable
private fun ExtensionLayer(
    image: ImageBitmap,
    imageZoom: Float,
    imageAspect: Float?,
    softenFrom: Float,
    softenTo: Float,
    moving: Boolean,
    frame: () -> ImageBitmap?,
    opacity: () -> Float,
    fades: Boolean,
) {
        val extension = remember { ExtensionPicture() }
        val shift = remember { floatArrayOf(0f) }
        Box(
            Modifier
                .fillMaxSize()
                .layout { measurable, constraints ->
                    val full = constraints.maxHeight
                    val slot = if (imageAspect != null) constraints.maxWidth / imageAspect else full.toFloat()
                    val fadeFrom = if (imageAspect != null) 1f - SLOT_FADE else softenFrom
                    val top = (minOf(fadeFrom, 1f) * slot - SMOOTH_PX * 2).toInt().coerceIn(0, full)
                    shift[0] = top.toFloat()
                    val placeable = measurable.measure(
                        constraints.copy(minHeight = full - top, maxHeight = full - top),
                    )
                    layout(constraints.maxWidth, full) { placeable.place(0, top) }
                }
                .then(if (fades) Modifier.graphicsLayer { alpha = opacity() } else Modifier)
                .then(if (moving) Modifier.smoothedUp() else Modifier)
                .drawBehind {
                    val shifted = shift[0]
                    translate(top = -shifted) {
                    val size = androidx.compose.ui.geometry.Size(size.width, size.height + shifted)
                    val slotHeight =
                        if (imageAspect != null) size.width / imageAspect else size.height
                    val fadeFrom = if (imageAspect != null) 1f - SLOT_FADE else softenFrom
                    val fadeTo = if (imageAspect != null) 1f else softenTo
                    val rowFraction = fadeFrom.coerceAtMost(0.995f)
                    val top = (minOf(fadeFrom, 1f) * slotHeight).coerceIn(0f, size.height)
                    if (top >= size.height) return@translate
                    val hold = if (size.height > top) {
                        ((fadeFrom * slotHeight - top) / (size.height - top)).coerceIn(0f, 0.9f)
                    } else {
                        0f
                    }

                    val source = frame() ?: image
                    val w = source.width
                    val h = source.height
                    val slotRatio = if (slotHeight > 0f) size.width / slotHeight else 1f
                    val shown = croppedTo(w, h, slotRatio, imageZoom)
                    val row = (shown.top + rowFraction * shown.height).roundToInt()
                        .coerceIn(shown.top, shown.bottom - 1)

                    val picture =
                        extension.at(source, row, shown.left, shown.width, shown.top, shown.bottom, hold)
                    drawImage(
                        image = picture,
                        dstOffset = IntOffset(0, top.roundToInt()),
                        dstSize = IntSize(size.width.roundToInt(), (size.height - top).roundToInt()),
                        filterQuality = FilterQuality.Low,
                    )
                    }
                },
        )
}

private class ExtensionPicture {
    private var key = -1L
    private var from: ImageBitmap? = null
    private var built: ImageBitmap? = null

    fun at(
        source: ImageBitmap,
        row: Int,
        left: Int,
        width: Int,
        top: Int,
        bottom: Int,
        hold: Float,
    ): ImageBitmap {
        val held = (hold * 100f).roundToInt().toLong()
        val wanted =
            (held shl 48) or (row.toLong() shl 32) or (left.toLong() shl 16) or width.toLong()
        built?.takeIf { key == wanted && from === source }?.let { return it }

        val bitmap = source.asAndroidBitmap()
        val sampled = FloatArray(width * 3)
        var rows = 0
        for (r in (row - EXTENSION_ROWS_ABOVE)..row) {
            if (r < top || r >= bottom) continue
            val line = IntArray(width)
            bitmap.getPixels(line, 0, width, left, r, width, 1)
            for (x in 0 until width) {
                val c = line[x]
                sampled[x * 3] += ((c shr 16) and 0xFF).toFloat()
                sampled[x * 3 + 1] += ((c shr 8) and 0xFF).toFloat()
                sampled[x * 3 + 2] += (c and 0xFF).toFloat()
            }
            rows++
        }
        for (i in sampled.indices) sampled[i] /= rows.coerceAtLeast(1)
        val dominant = groundOf(bitmap, left, width, top, bottom, row)

        val blurred = FloatArray(width * 3)
        val hsv = FloatArray(3)
        val out = IntArray(width * EXTENSION_ROWS)
        for (r in 0 until EXTENSION_ROWS) {
            val down = r.toFloat() / (EXTENSION_ROWS - 1)
            val t = if (hold < 1f) ((down - hold) / (1f - hold)).coerceIn(0f, 1f) else 0f
            val sigma = (EXTENSION_NEAR + (EXTENSION_FAR - EXTENSION_NEAR) * t) * width
            blurAcross(sampled, width, sigma, blurred)
            val toward = EXTENSION_CONVERGE * eased(((t - 0.2f) / 0.8f).coerceIn(0f, 1f))
            val deepen = EXTENSION_DEEPEN * eased(t)
            for (x in 0 until width) {
                val red = blurred[x * 3] + (dominant[0] - blurred[x * 3]) * toward
                val green = blurred[x * 3 + 1] + (dominant[1] - blurred[x * 3 + 1]) * toward
                val blue = blurred[x * 3 + 2] + (dominant[2] - blurred[x * 3 + 2]) * toward
                android.graphics.Color.RGBToHSV(
                    red.roundToInt().coerceIn(0, 255),
                    green.roundToInt().coerceIn(0, 255),
                    blue.roundToInt().coerceIn(0, 255),
                    hsv,
                )
                hsv[1] = (hsv[1] * (1f + EXTENSION_RICHER * deepen / EXTENSION_DEEPEN)).coerceAtMost(1f)
                hsv[2] = hsv[2] * (1f - deepen)
                out[r * width + x] = android.graphics.Color.HSVToColor(hsv)
            }
        }
        val picture = android.graphics.Bitmap
            .createBitmap(out, width, EXTENSION_ROWS, android.graphics.Bitmap.Config.ARGB_8888)
            .asImageBitmap()
        key = wanted
        from = source
        built = picture
        return picture
    }

    private fun groundOf(
        bitmap: android.graphics.Bitmap,
        left: Int,
        width: Int,
        top: Int,
        bottom: Int,
        row: Int,
    ): FloatArray {
        val from = maxOf(top, row - ((bottom - top) * GROUND_BAND).roundToInt())
        val counts = Array(3) { FloatArray(256) }
        val line = IntArray(width)
        val hsv = FloatArray(3)
        var total = 0f
        for (r in from..row) {
            if (r < top || r >= bottom) continue
            bitmap.getPixels(line, 0, width, left, r, width, 1)
            for (c in line) {
                val red = (c shr 16) and 0xFF
                val green = (c shr 8) and 0xFF
                val blue = c and 0xFF
                android.graphics.Color.RGBToHSV(red, green, blue, hsv)
                val vivid = hsv[1] * hsv[2]
                val weight = 1f + GROUND_VIVID * vivid * vivid
                counts[0][red] += weight
                counts[1][green] += weight
                counts[2][blue] += weight
                total += weight
            }
        }
        if (total == 0f) return floatArrayOf(0f, 0f, 0f)
        return FloatArray(3) { channel ->
            var seen = 0f
            var value = 0
            while (value < 255) {
                seen += counts[channel][value]
                if (seen >= total / 2f) break
                value++
            }
            value.toFloat()
        }
    }

    private fun blurAcross(rgb: FloatArray, width: Int, sigma: Float, out: FloatArray) {
        val radius = (sigma * 2.5f).roundToInt().coerceAtLeast(1)
        val weights = FloatArray(radius * 2 + 1) { i ->
            val d = (i - radius).toFloat()
            kotlin.math.exp(-(d * d) / (2f * sigma * sigma))
        }
        for (x in 0 until width) {
            var red = 0f
            var green = 0f
            var blue = 0f
            var total = 0f
            for (i in weights.indices) {
                val sx = (x + i - radius).coerceIn(0, width - 1)
                val w = weights[i]
                red += rgb[sx * 3] * w
                green += rgb[sx * 3 + 1] * w
                blue += rgb[sx * 3 + 2] * w
                total += w
            }
            out[x * 3] = red / total
            out[x * 3 + 1] = green / total
            out[x * 3 + 2] = blue / total
        }
    }

    private fun eased(t: Float): Float = t * t * (3f - 2f * t)
}

private class MotionFrames {
    class Frame(val sharp: ImageBitmap, val soft: ImageBitmap)

    val latest = mutableStateOf<Frame?>(null)

    val previous = mutableStateOf<Frame?>(null)

    private var blend: FloatArray? = null
    private var blendWidth = 0
    private var blendHeight = 0

    fun take(bitmap: android.graphics.Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val kept = blend?.takeIf { blendWidth == width && blendHeight == height }
        val running = kept ?: FloatArray(pixels.size * 3).also {
            blend = it
            blendWidth = width
            blendHeight = height
        }
        val follow = if (kept == null) 1f else FRAME_FOLLOW
        for (i in pixels.indices) {
            val c = pixels[i]
            running[i * 3] += (((c shr 16) and 0xFF) - running[i * 3]) * follow
            running[i * 3 + 1] += (((c shr 8) and 0xFF) - running[i * 3 + 1]) * follow
            running[i * 3 + 2] += ((c and 0xFF) - running[i * 3 + 2]) * follow
            pixels[i] = (0xFF shl 24) or
                (running[i * 3].roundToInt().coerceIn(0, 255) shl 16) or
                (running[i * 3 + 1].roundToInt().coerceIn(0, 255) shl 8) or
                running[i * 3 + 2].roundToInt().coerceIn(0, 255)
        }
        val smooth = android.graphics.Bitmap
            .createBitmap(pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val next = Frame(smooth.asImageBitmap(), softened(smooth).asImageBitmap())
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            previous.value = latest.value
            latest.value = next
        }
    }

    private fun softened(bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val scratch = pixels.copyOf()
        val firstRow = (height * SOFT_ROWS_FROM).toInt()
        repeat(2) {
            boxBlur(pixels, scratch, width, height, horizontal = true, firstRow = firstRow)
            boxBlur(scratch, pixels, width, height, horizontal = false, firstRow = firstRow)
        }
        return android.graphics.Bitmap.createBitmap(pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888)
    }

    private fun boxBlur(from: IntArray, to: IntArray, width: Int, height: Int, horizontal: Boolean, firstRow: Int = 0) {
        val radius = FRAME_SOFTEN
        for (y in firstRow until height) {
            for (x in 0 until width) {
                var red = 0
                var green = 0
                var blue = 0
                var count = 0
                for (d in -radius..radius) {
                    val sx = if (horizontal) (x + d).coerceIn(0, width - 1) else x
                    val sy = if (horizontal) y else (y + d).coerceIn(0, height - 1)
                    val c = from[sy * width + sx]
                    red += (c shr 16) and 0xFF
                    green += (c shr 8) and 0xFF
                    blue += c and 0xFF
                    count++
                }
                to[y * width + x] = (0xFF shl 24) or
                    ((red / count) shl 16) or ((green / count) shl 8) or (blue / count)
            }
        }
    }
}

private fun Modifier.smoothedUp(): Modifier =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        graphicsLayer {
            renderEffect = androidx.compose.ui.graphics.BlurEffect(SMOOTH_PX, SMOOTH_PX, androidx.compose.ui.graphics.TileMode.Clamp)
        }
    } else {
        this
    }

private const val SMOOTH_PX = 20f

private const val SOFT_ROWS_FROM = 0.4f

private const val FRAME_SOFTEN = 4

private const val FRAME_FOLLOW = 0.45f

private const val MOTION_FRAME_FADE_MS = 140

private fun maskedPictureBrush(
    image: ImageBitmap,
    zoom: Float,
    size: androidx.compose.ui.geometry.Size,
    stops: Array<Pair<Float, Color>>,
): androidx.compose.ui.graphics.Brush {
    val crop = croppedTo(image.width, image.height, size.width / size.height, zoom)
    val picture = android.graphics.BitmapShader(
        image.asAndroidBitmap(),
        android.graphics.Shader.TileMode.CLAMP,
        android.graphics.Shader.TileMode.CLAMP,
    ).apply {
        setLocalMatrix(android.graphics.Matrix().apply {
            setRectToRect(
                android.graphics.RectF(
                    crop.left.toFloat(), crop.top.toFloat(),
                    crop.right.toFloat(), crop.bottom.toFloat(),
                ),
                android.graphics.RectF(0f, 0f, size.width, size.height),
                android.graphics.Matrix.ScaleToFit.FILL,
            )
        })
    }
    val mask = android.graphics.LinearGradient(
        0f, 0f, 0f, size.height,
        stops.map { it.second.toArgb() }.toIntArray(),
        stops.map { it.first }.toFloatArray(),
        android.graphics.Shader.TileMode.CLAMP,
    )
    return ShaderBrush(android.graphics.ComposeShader(picture, mask, android.graphics.PorterDuff.Mode.DST_IN))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCropped(
    image: ImageBitmap,
    zoom: Float = 1f,
) {
    val shown = croppedTo(image.width, image.height, size.width / size.height, zoom)
    drawImage(
        image = image,
        srcOffset = shown.topLeft,
        srcSize = shown.size,
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        filterQuality = FilterQuality.Low,
    )
}

private const val EXTENSION_PX = 128

private const val EXTENSION_ROWS_ABOVE = 3

private const val EXTENSION_ROWS = 24

private const val EXTENSION_NEAR = 0.05f
private const val EXTENSION_FAR = 0.40f

private const val GROUND_BAND = 0.35f

private const val GROUND_VIVID = 3f

private const val EXTENSION_CONVERGE = 0.85f

private const val EXTENSION_DEEPEN = 0.40f

private const val EXTENSION_RICHER = 0.25f

private fun croppedTo(w: Int, h: Int, ratio: Float, zoom: Float): IntRect {
    val fit = if (w.toFloat() / h > ratio) {
        val visible = (h * ratio).roundToInt().coerceIn(1, w)
        IntRect(IntOffset((w - visible) / 2, 0), IntSize(visible, h))
    } else {
        val visible = (w / ratio).roundToInt().coerceIn(1, h)
        IntRect(IntOffset(0, (h - visible) / 2), IntSize(w, visible))
    }
    if (zoom <= 1f) return fit
    val width = (fit.width / zoom).roundToInt().coerceAtLeast(1)
    val height = (fit.height / zoom).roundToInt().coerceAtLeast(1)
    return IntRect(IntOffset(fit.left + (fit.width - width) / 2, fit.top), IntSize(width, height))
}

private fun Modifier.pictureSlot(aspect: Float): Modifier =
    layout { measurable, constraints ->
        val width = constraints.maxWidth
        val height = (width / aspect).roundToInt().coerceIn(0, constraints.maxHeight)
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(width, height) { placeable.place(0, 0) }
    }

private const val SLOT_FADE = 0.10f
private const val SLOT_BLUR_LEAD = 0.03f
private const val SLOT_BLUR_TAIL = 0.05f

private const val TALL_ART_SOFT_FROM = 0.70f

const val TALL_ART_ZOOM = (1f - SLOT_FADE - SLOT_BLUR_LEAD) / TALL_ART_SOFT_FROM


private fun fading(from: Float, to: Float): Array<Pair<Float, Color>> {
    val steps = 8
    return Array(steps + 1) { index ->
        val t = index.toFloat() / steps
        val eased = t * t * (3f - 2f * t)
        (from + (to - from) * t) to Color.Black.copy(alpha = 1f - eased)
    }
}
