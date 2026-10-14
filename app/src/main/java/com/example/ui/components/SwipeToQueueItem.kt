package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Track

@Composable
fun SwipeToQueueTrackItem(
    track: Track,
    isPlaying: Boolean,
    isCurrentTrack: Boolean,
    onTrackClick: () -> Unit,
    onQueue: (Track) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToPlaylistClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onQueue(track)
                    false // Snap back into the list
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    if (onAddToPlaylistClick != null) {
                        onAddToPlaylistClick()
                    } else {
                        onQueue(track)
                    }
                    false // Snap back into the list
                }
                else -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val isSwiping = dismissState.targetValue != SwipeToDismissBoxValue.Settled ||
                    dismissState.currentValue != SwipeToDismissBoxValue.Settled

            if (isSwiping) {
                val isQueue = direction == SwipeToDismissBoxValue.StartToEnd
                val bgColor by animateColorAsState(
                    targetValue = if (isQueue) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    label = "swipe_bg"
                )

                val tintColor by animateColorAsState(
                    targetValue = if (isQueue) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    label = "swipe_tint"
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(bgColor, RoundedCornerShape(12.dp))
                        .padding(horizontal = 20.dp),
                    contentAlignment = if (isQueue) Alignment.CenterStart else Alignment.CenterEnd
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isQueue) Icons.AutoMirrored.Filled.QueueMusic else Icons.Default.PlaylistAdd,
                            contentDescription = if (isQueue) "Queue track" else "Add to playlist",
                            tint = tintColor,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = if (isQueue) "Add to Queue" else "Add to Playlist",
                            color = tintColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                // Completely empty when not actively dragging to prevent any visual overlap
                Spacer(modifier = Modifier.fillMaxSize())
            }
        }
    ) {
        TrackItemContent(
            track = track,
            isPlaying = isPlaying,
            isCurrentTrack = isCurrentTrack,
            onTrackClick = onTrackClick,
            onToggleFavorite = { onToggleFavorite(track.id) }
        )
    }
}

@Composable
fun TrackItemContent(
    track: Track,
    isPlaying: Boolean,
    isCurrentTrack: Boolean,
    onTrackClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    // Ensure Surface is completely opaque with solid container colors so background never bleeds through
    Surface(
        color = if (isCurrentTrack) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = RoundedCornerShape(12.dp),
        border = if (isCurrentTrack) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else null,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTrackClick)
            .testTag("track_item_${track.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Artwork
            Box(contentAlignment = Alignment.Center) {
                AlbumArtwork(
                    track = track,
                    size = 48.dp,
                    shapeRadius = 10.dp,
                    isRotatingVinyl = isCurrentTrack && isPlaying,
                    isPlaying = isPlaying
                )
                if (isCurrentTrack && isPlaying) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Playing",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Title, Artist, and Format Badges
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrentTrack) FontWeight.Bold else FontWeight.Medium,
                    color = if (isCurrentTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${track.artist} • ${track.album}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = track.formatLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Duration
            Text(
                text = track.durationFormatted,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Favorite Button
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("fav_button_${track.id}")
            ) {
                Icon(
                    imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (track.isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (track.isFavorite) Color(0xFFEF4444) else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
