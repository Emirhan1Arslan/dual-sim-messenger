package com.ciftsim.mesajlar.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

private val bitmapCache = LruCache<String, Bitmap>(24)

/** Resmi en uzun kenarı [maxSide] pikseli geçmeyecek şekilde küçülterek çözer. */
private fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) {
            decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val largest = max(info.size.width, info.size.height)
        if (largest > maxSide) decoder.setTargetSampleSize(largest / maxSide + 1)
    }

/** content:// adresindeki resmi (MMS eki ya da seçilen fotoğraf) arka planda yükleyip gösterir. */
@Composable
fun UriImage(
    uri: String,
    modifier: Modifier = Modifier,
    maxSide: Int = 720,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val key = "$uri@$maxSide"
    val bitmap by produceState(bitmapCache.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                try {
                    decode(context, Uri.parse(uri), maxSide).also { bitmapCache.put(key, it) }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = "Fotoğraf",
            contentScale = contentScale,
            modifier = modifier,
        )
    } else {
        Box(
            modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

/** Resmi tam ekran gösterir; dokununca kapanır. */
@Composable
fun FullScreenImage(uri: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            UriImage(
                uri = uri,
                maxSide = 2048,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Resim olmayan eki (video, ses, kartvizit…) uygun uygulamayla açar. */
fun openAttachment(context: Context, uri: String, mime: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(uri), mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    } catch (_: Exception) {
        Toast.makeText(context, "Bu eki açabilecek uygulama bulunamadı", Toast.LENGTH_SHORT).show()
    }
}

fun dial(context: Context, number: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
    } catch (_: Exception) {
        Toast.makeText(context, "Telefon uygulaması bulunamadı", Toast.LENGTH_SHORT).show()
    }
}
