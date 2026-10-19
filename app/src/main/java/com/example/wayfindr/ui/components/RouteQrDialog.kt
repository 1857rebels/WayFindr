package com.example.wayfindr.ui.components

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.wayfindr.data.SavedLocation
import com.example.wayfindr.ui.WayfindrIcons
import com.example.wayfindr.ui.theme.*
import com.example.wayfindr.util.RouteQr

/** Share a saved destination: its QR (name on top), then share the image or save it to the gallery. */
@Composable
fun RouteQrDialog(loc: SavedLocation, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val bmp = remember(loc) { RouteQr.image(loc) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Surf1).padding(16.dp),
        ) {
            if (bmp == null) {
                Text("This route has too many stops to fit in a QR code.", color = TextMid, fontSize = 14.sp,
                    fontFamily = GeistFamily, modifier = Modifier.padding(vertical = 24.dp))
            } else {
                Image(
                    bmp.asImageBitmap(), contentDescription = "QR code for ${loc.name}",
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
                )
                Text("Scan with the phone camera to open in Wayfindr", color = TextLo, fontSize = 12.sp,
                    fontFamily = GeistFamily, modifier = Modifier.padding(top = 10.dp, bottom = 14.dp))
                WayfindrBtn(
                    label = "Share image",
                    onClick = { RouteQr.shareImage(ctx, bmp, loc.name) },
                    icon = WayfindrIcons.Share,
                    variant = BtnVariant.Primary,
                    size = BtnSize.Md,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                WayfindrBtn(
                    label = "Save to gallery",
                    onClick = {
                        val ok = RouteQr.saveToGallery(ctx, bmp, loc.name)
                        Toast.makeText(ctx, if (ok) "Saved to Pictures/Wayfindr" else "Couldn't save the image",
                            Toast.LENGTH_SHORT).show()
                    },
                    icon = WayfindrIcons.Download,
                    variant = BtnVariant.Secondary,
                    size = BtnSize.Md,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(4.dp))
            WayfindrBtn(
                label = "Close",
                onClick = onDismiss,
                variant = BtnVariant.Ghost,
                size = BtnSize.Md,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
