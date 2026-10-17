package com.example.wayfindr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wayfindr.ui.theme.*

// ---- Shape constants ----
val CardShape    = RoundedCornerShape(18.dp)
val BtnShape     = RoundedCornerShape(14.dp)
val InputShape   = RoundedCornerShape(12.dp)
val ChipShape    = CircleShape
val IconBtnShape = RoundedCornerShape(14.dp)

// ---- Card ----
@Composable
fun WayfindrCard(
    modifier: Modifier = Modifier,
    glow: Boolean = false,
    padding: Dp = 18.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val border = if (glow)
        Modifier.border(1.dp, GoldTint2, CardShape)
    else
        Modifier.border(1.dp, Line, CardShape)

    Column(
        modifier = modifier
            .clip(CardShape)
            .background(Surf1)
            .then(border)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

// ---- Buttons ----
enum class BtnVariant { Primary, Secondary, Ghost, Danger }
enum class BtnSize { Lg, Md, Sm }

@Composable
fun WayfindrBtn(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    variant: BtnVariant = BtnVariant.Primary,
    size: BtnSize = BtnSize.Md,
    enabled: Boolean = true,
) {
    val (bgColor, fgColor, borderColor, hasShadow) = when (variant) {
        BtnVariant.Primary   -> listOf(Gold, OnGold, Color.Transparent, true)
        BtnVariant.Secondary -> listOf(Surf2, TextHi, Line2, false)
        BtnVariant.Ghost     -> listOf(Color.Transparent, TextMid, Line2, false)
        BtnVariant.Danger    -> listOf(Color.Transparent, Alert, Alert.copy(alpha = 0.4f), false)
    }
    val height = when (size) { BtnSize.Lg -> 58.dp; BtnSize.Md -> 50.dp; BtnSize.Sm -> 40.dp }
    val fontSize = when (size) { BtnSize.Lg -> 16.sp; BtnSize.Md -> 15.sp; BtnSize.Sm -> 13.5.sp }
    val iconSize = when (size) { BtnSize.Sm -> 17.dp; else -> 20.dp }

    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(height)
            .clip(BtnShape)
            .background(bgColor as Color)
            .border(1.dp, borderColor as Color, BtnShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fgColor as Color,
                modifier = Modifier.size(iconSize),
            )
            Spacer(Modifier.width(9.dp))
        }
        Text(
            text = label,
            color = fgColor as Color,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistFamily,
            letterSpacing = (-0.15).sp,
            maxLines = 1,
        )
    }
}

// ---- Icon button (square rounded) ----
@Composable
fun WayfindrIconBtn(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    active: Boolean = false,
    tint: Color? = null,
) {
    val bg = if (active) GoldTint else Surf2
    val bd = if (active) GoldTint2 else Line2
    val iconTint = tint ?: if (active) Gold else TextMid

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(IconBtnShape)
            .background(bg)
            .border(1.dp, bd, IconBtnShape)
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
    }
}

// ---- Chip ----
enum class ChipTone { Gold, Warn, Alert, Off, Neutral }

@Composable
fun WayfindrChip(
    label: String,
    tone: ChipTone = ChipTone.Neutral,
    dot: Boolean = false,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    val (bg, fg, bd) = when (tone) {
        ChipTone.Gold    -> Triple(GoldTint, Gold, GoldTint2)
        ChipTone.Warn    -> Triple(Warn.copy(alpha = 0.13f), Warn, Warn.copy(alpha = 0.3f))
        ChipTone.Alert   -> Triple(Alert.copy(alpha = 0.13f), Alert, Alert.copy(alpha = 0.32f))
        ChipTone.Off     -> Triple(Surf1, TextLo, Line)
        ChipTone.Neutral -> Triple(Surf2, TextMid, Line2)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = modifier
            .height(30.dp)
            .clip(ChipShape)
            .background(bg)
            .border(1.dp, bd, ChipShape)
            .padding(horizontal = 12.dp),
    ) {
        if (dot) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(fg))
        }
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        }
        Text(
            label, color = fg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = GeistFamily, maxLines = 1,
        )
    }
}

// ---- Toggle ----
@Composable
fun WayfindrToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val track = if (on) Gold else Surf3
    val bd    = if (on) Gold else Line3
    val knob  = if (on) OnGold else TextMid
    val knobOffset = if (on) 22.dp else 2.5.dp

    Box(
        modifier = Modifier
            .width(50.dp).height(30.dp)
            .clip(CircleShape)
            .background(track)
            .border(1.dp, bd, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onChange(!on) },
    ) {
        Box(
            Modifier
                .padding(start = knobOffset, top = 2.5.dp)
                .size(23.dp)
                .clip(CircleShape)
                .background(knob)
        )
    }
}

// ---- Segmented control ----
@Composable
fun WayfindrSegmented(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Surf1)
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { opt ->
            val active = opt == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) Surf3 else Color.Transparent)
                    .clickable { onSelect(opt) },
            ) {
                Text(
                    opt, color = if (active) TextHi else TextLo,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistFamily,
                )
            }
        }
    }
}

// ---- Divider ----
@Composable
fun WayfindrDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Line))
}

// ---- Eyebrow label ----
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = TextLo,
        fontSize = 11.sp,
        letterSpacing = 0.16.sp,
        fontFamily = GeistMonoFamily,
        fontWeight = FontWeight.Normal,
        modifier = modifier,
    )
}

// ---- List row ----
enum class IconTone { Neutral, Gold, Warn, Alert }

@Composable
fun WayfindrRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTone: IconTone = IconTone.Neutral,
    sub: String? = null,
    right: String? = null,
    rightSub: String? = null,
    accentRight: Boolean = false,
    trailingIcon: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val (iconBg, iconFg) = when (iconTone) {
        IconTone.Gold   -> GoldTint to Gold
        IconTone.Alert  -> Alert.copy(alpha = 0.13f) to Alert
        IconTone.Warn   -> Warn.copy(alpha = 0.13f) to Warn
        IconTone.Neutral -> Surf2 to TextMid
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp, horizontal = 4.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        if (icon != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconBg)
                    .border(1.dp, Line, RoundedCornerShape(12.dp)),
            ) {
                Icon(icon, contentDescription = null, tint = iconFg, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title, color = TextHi, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = GeistFamily, letterSpacing = (-0.15).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Text(sub, color = TextLo, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (right != null || rightSub != null) {
            Column(horizontalAlignment = Alignment.End) {
                if (right != null) {
                    Text(
                        right,
                        color = if (accentRight) Gold else TextHi,
                        fontSize = 14.5.sp, fontWeight = FontWeight.Medium,
                        fontFamily = GeistMonoFamily,
                    )
                }
                if (rightSub != null) {
                    Text(rightSub, color = TextLo, fontSize = 11.5.sp)
                }
            }
        }
        if (trailingIcon || (onClick != null && right == null)) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null, tint = TextLo, modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ---- Screen scaffold (scrollable body) ----
@Composable
fun ScreenHeader(
    modifier: Modifier = Modifier,
    title: String? = null,
    eyebrow: String? = null,
    hint: String? = null,
    wordmark: Boolean = false,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp)
            .defaultMinSize(minHeight = 44.dp),
    ) {
        if (onBack != null) {
            WayfindrIconBtn(
                icon = Icons.Outlined.ChevronLeft,
                onClick = onBack,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        Box(Modifier.weight(1f)) {
            if (wordmark) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "WayfindR",
                        color = TextHi, fontFamily = GeistMonoFamily,
                        fontWeight = FontWeight.Bold, fontSize = 21.sp,
                        letterSpacing = 0.14.sp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Gold))
                }
            } else {
                Column {
                    if (eyebrow != null) Eyebrow(eyebrow, Modifier.padding(bottom = 3.dp))
                    if (title != null) {
                        Text(
                            title, color = TextHi, fontSize = 23.sp,
                            fontWeight = FontWeight.Bold, fontFamily = GeistFamily,
                            letterSpacing = (-0.46).sp,
                        )
                    }
                    // One-line "what this screen is for" guidance, shown right under the title.
                    if (hint != null) {
                        Text(
                            hint, color = TextLo, fontSize = 12.sp, fontFamily = GeistFamily,
                            lineHeight = 15.sp, modifier = Modifier.padding(top = 3.dp, end = 8.dp),
                        )
                    }
                }
            }
        }
        trailing?.invoke()
    }
}
