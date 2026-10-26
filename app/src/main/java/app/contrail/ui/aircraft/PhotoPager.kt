package app.contrail.ui.aircraft

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import app.contrail.data.enrich.Photo
import coil3.compose.AsyncImage

/** Swipeable aircraft photos. Photographer credit and source link are always shown (site terms). */
@Composable
fun PhotoPager(photos: List<Photo>, aircraftLabel: String) {
    val uri = LocalUriHandler.current
    val pager = rememberPagerState { photos.size }
    HorizontalPager(
        state = pager,
        pageSpacing = 8.dp,
        // 3:2 is airport-data's usual shape; bottom-anchored so its burned-in photographer strip is never cropped.
        modifier = Modifier.fillMaxWidth().aspectRatio(3f / 2f).clip(MaterialTheme.shapes.medium),
    ) { i ->
        val p = photos[i]
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable(enabled = p.pageUrl != null) { p.pageUrl?.let { runCatching { uri.openUri(it) } } }) { // runCatching: no browser installed
            AsyncImage(
                model = p.fullUrl,
                placeholder = null,
                contentDescription = "Photo of $aircraftLabel",
                contentScale = ContentScale.Crop,
                alignment = Alignment.BottomCenter,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent)))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    listOfNotNull(p.photographer?.let { "© $it" }, p.site, if (photos.size > 1) "${i + 1}/${photos.size}" else null).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
        }
    }
}
