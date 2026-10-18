package org.anmon.ui.components

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.anmon.data.ArtworkCache
import org.anmon.model.Track
import kotlin.math.absoluteValue

// Color palettes for procedural album artwork
private val AlbumGradients = listOf(
    listOf(Color(0xFF8B5CF6), Color(0xFF6366F1), Color(0xFF3B82F6)),
    listOf(Color(0xFFEC4899), Color(0xFF8B5CF6), Color(0xFF4F46E5)),
    listOf(Color(0xFF10B981), Color(0xFF06B6D4), Color(0xFF3B82F6)),
    listOf(Color(0xFFF59E0B), Color(0xFFEF4444), Color(0xFF8B5CF6)),
    listOf(Color(0xFF06B6D4), Color(0xFF3B82F6), Color(0xFF1D4ED8)),
    listOf(Color(0xFF84CC16), Color(0xFF10B981), Color(0xFF059669)),
    listOf(Color(0xFFA855F7), Color(0xFFD946EF), Color(0xFFEC4899)),
    listOf(Color(0xFF14B8A6), Color(0xFF0EA5E9), Color(0xFF6366F1))
)

@Composable
fun AlbumArtwork(
    track: Track?,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    shapeRadius: Dp = 12.dp,
    isRotatingVinyl: Boolean = false,
    isPlaying: Boolean = false
) {
    val context = LocalContext.current
    var artworkBytes by remember(track?.id, track?.uri) { mutableStateOf<ByteArray?>(null) }

    LaunchedEffect(track?.id, track?.uri) {
        if (track != null && track.hasEmbeddedArtwork) {
            artworkBytes = ArtworkCache.getArtworkBytes(context, track)
        } else {
            artworkBytes = null
        }
    }

    val rotation = if (isRotatingVinyl && isPlaying) {
        val infiniteTransition = rememberInfiniteTransition(label = "vinyl_spin")
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(12000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "spin"
        ).value
    } else 0f

    val shape = if (isRotatingVinyl) CircleShape else RoundedCornerShape(shapeRadius)

    Box(
        modifier = modifier
            .size(size)
            .shadow(if (isRotatingVinyl) 12.dp else 4.dp, shape)
            .clip(shape)
            .rotate(rotation),
        contentAlignment = Alignment.Center
    ) {
        if (artworkBytes != null) {
            val bitmap = remember(artworkBytes) {
                artworkBytes?.let {
                    try {
                        BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                    } catch (_: Exception) {
                        null
                    }
                }
            }

            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Album art for ${track?.album}",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                ProceduralCoverArt(track = track, size = size, isVinyl = isRotatingVinyl)
            }
        } else {
            ProceduralCoverArt(track = track, size = size, isVinyl = isRotatingVinyl)
        }

        // Overlay vinyl center hole if in vinyl mode
        if (isRotatingVinyl) {
            Box(
                modifier = Modifier
                    .size(size * 0.22f)
                    .clip(CircleShape)
                    .background(Color(0xFF0A0914))
                    .border(1.5.dp, Color(0xFF2A283E), CircleShape)
            )
        }
    }
}

@Composable
fun ProceduralCoverArt(
    track: Track?,
    size: Dp,
    isVinyl: Boolean
) {
    val seed = (track?.album?.hashCode() ?: track?.title?.hashCode() ?: 42).absoluteValue
    val colors = AlbumGradients[seed % AlbumGradients.size]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = colors,
                    start = Offset.Zero,
                    end = Offset.Infinite
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = this.size.width
            val canvasHeight = this.size.height
            val center = Offset(canvasWidth / 2f, canvasHeight / 2f)

            // Draw concentric vinyl grooves
            val grooveCount = if (isVinyl) 6 else 3
            for (i in 1..grooveCount) {
                val radius = (canvasWidth / 2f) * (0.35f + (i * 0.1f))
                drawCircle(
                    color = Color.White.copy(alpha = 0.08f),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 1.2f)
                )
            }
        }

        if (size >= 48.dp) {
            val glyph = track?.album?.take(1)?.uppercase() ?: track?.title?.take(1)?.uppercase() ?: "A"
            if (size >= 80.dp) {
                Box(
                    modifier = Modifier
                        .size(size * 0.38f)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = glyph,
                        color = Color.White,
                        fontSize = (size.value * 0.18f).sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(size * 0.45f)
                )
            }
        }
    }
}
