package com.infraxcoders.bmpcc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infraxcoders.bmpcc.core.Coverage
import java.util.Locale

object Brand {
    /** Buttons, selected chips, highlights. */
    val accent = Color(0xFF3D6BFF)
    /** Accent for small text on the navy background (more contrast). */
    val accentText = Color(0xFF8FAAFF)
    val frameLine = Color(0xFF8FB4FF)
    val locked = Color(0xFFFF4B33)
    val record = Color(0xFFFF4B33)
    val panel = Color(0x8C000000)
    /** Deep navy app background. */
    val background = Color(0xFF0B1E45)
    /** Rows and cards on the navy background. */
    val surface = Color(0xFF14295A)
    /** Light cards, chips and round buttons (with [ink] text). */
    val light = Color(0xFFEEF0F5)
    val lightChip = Color(0xFFE3E9F8)
    val ink = Color(0xFF0B1E45)
    val muted = Color(0xFF9AA8C7)
}

private val colors = darkColorScheme(
    primary = Brand.accent,
    onPrimary = Color.White,
    secondary = Brand.frameLine,
    background = Brand.background,
    surface = Brand.background,
    surfaceVariant = Brand.surface,
    surfaceContainer = Brand.surface,
    surfaceContainerHigh = Color(0xFF1B3570),
    surfaceContainerLow = Brand.surface,
    surfaceContainerHighest = Color(0xFF22407F),
    onSurfaceVariant = Brand.muted,
    outline = Color(0xFF3A5591),
)

@Composable
fun BMPCCTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)

fun Double.degreesText(): String = String.format(Locale.US, "%.1f°", this)
fun fmt(pattern: String, vararg args: Any?): String = String.format(Locale.US, pattern, *args)

/** A screen with a top bar, a back arrow (when [onBack] is given) and optional actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = Brand.background,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Brand.background),
            )
        },
        content = content,
    )
}

/** Rows of a section sit on a card-coloured band, so a section reads as one grouped card. */
fun Modifier.cardRow(): Modifier = this.fillMaxWidth().padding(horizontal = 12.dp).background(Brand.surface)

/** Thin divider between rows inside a card. */
@Composable
fun RowDivider() = HorizontalDivider(color = Color(0x1FFFFFFF), modifier = Modifier.padding(horizontal = 12.dp).padding(start = 16.dp))

/** Section header inside a list. */
fun LazyListScope.section(title: String, footer: String? = null, content: LazyListScope.() -> Unit) {
    item(key = "h-$title") {
        Text(
            title.uppercase(), style = MaterialTheme.typography.labelMedium, color = Brand.accentText,
            modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 22.dp, bottom = 8.dp),
        )
    }
    content()
    if (footer != null) item(key = "f-$title") {
        Text(
            footer, style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/** A two-column row: label on the left, value (or content) on the right. */
@Composable
fun LabeledRow(label: String, value: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .cardRow()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        if (value != null) Text(value, color = Color(0xFFB8B8C0), fontFamily = FontFamily.Default, maxLines = 2,
            modifier = Modifier.padding(start = 12.dp))
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
    RowDivider()
}

/** A text field that edits a saved value. */
@Composable
fun Field(
    label: String, value: String, onChange: (String) -> Unit, singleLine: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text, modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine,
        minLines = if (singleLine) 1 else 2, maxLines = if (singleLine) 1 else 8,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.cardRow().padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** A row that opens a menu of choices. */
@Composable
fun <T> ChoiceRow(label: String, value: T, options: List<T>, text: (T) -> String, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        LabeledRow(label, text(value), onClick = { open = true }) {
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Color.Gray)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(text(o)) }, onClick = { onChange(o); open = false })
            }
        }
    }
}

/** Preset choices that also keep a custom value (e.g. one imported from another app). */
@Composable
fun PresetRow(label: String, value: String, presets: List<String>, onChange: (String) -> Unit) {
    val all = if (value.isEmpty() || value in presets) presets else listOf(value) + presets
    ChoiceRow(label, value, all, { it.ifEmpty { "—" } }, onChange)
}

/** Small rounded label. */
@Composable
fun Tag(text: String, color: Color = Brand.accent) {
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(color.copy(alpha = 0.28f), RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
fun Tags(content: @Composable RowScope.() -> Unit) =
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = content)

/** Coloured verdict for a lens on a sensor area. */
@Composable
fun CoverageBadge(coverage: Coverage, nominal: Boolean = false, modifier: Modifier = Modifier) {
    val (icon, color) = when (coverage) {
        Coverage.FULL -> Icons.Filled.CheckCircle to Color(0xFF34C759)
        Coverage.CORNERS_VIGNETTE -> Icons.Filled.RemoveCircle to Color(0xFFFFCC00)
        Coverage.VIGNETTES -> Icons.Filled.Cancel to Color(0xFFFF3B30)
        Coverage.UNKNOWN -> Icons.Filled.Help to Color.Gray
    }
    Icon(icon, contentDescription = coverage.label, tint = color.copy(alpha = if (nominal) 0.55f else 1f), modifier = modifier.size(20.dp))
}

@Composable
fun Hint(text: String) = Text(
    text, style = MaterialTheme.typography.bodySmall, color = Color.Gray,
    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
)

@Composable
fun MonoText(text: String, color: Color = Color.LightGray) =
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color)

@Composable
fun Column2(content: @Composable () -> Unit) = Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { content() }
