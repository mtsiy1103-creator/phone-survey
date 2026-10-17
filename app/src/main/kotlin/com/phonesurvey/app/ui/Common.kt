package com.phonesurvey.app.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import com.phonesurvey.core.FixQuality
import java.util.Locale

fun fmt(v: Double?, digits: Int = 3): String = v?.let { String.format(Locale.US, "%.${digits}f", it) } ?: "-"

fun qualityColor(q: FixQuality?): Color = when (q) {
    FixQuality.RTK_FIX -> Color(0xFF2E7D32)
    FixQuality.RTK_FLOAT -> Color(0xFFF9A825)
    FixQuality.DGPS, FixQuality.PPS -> Color(0xFF1565C0)
    FixQuality.SINGLE, FixQuality.ESTIMATED, FixQuality.MANUAL, FixQuality.SIMULATION -> Color(0xFFC62828)
    FixQuality.INVALID, null -> Color(0xFF757575)
}

/**
 * Text field for a decimal number. Keeps the user's text while typing and reports
 * only values that parse.
 */
@Composable
fun NumberField(
    label: String,
    value: Double,
    onValue: (Double) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String = "",
    allowNegative: Boolean = false,
) {
    var text by remember { mutableStateOf(trimNumber(value)) }
    LaunchedEffect(value) {
        if (text.toDoubleOrNull() != value) text = trimNumber(value)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.toDoubleOrNull()?.let { if (allowNegative || it >= 0) onValue(it) }
        },
        label = { Text(label) },
        suffix = { if (suffix.isNotEmpty()) Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (allowNegative) KeyboardType.Text else KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
fun IntField(label: String, value: Int, onValue: (Int) -> Unit, modifier: Modifier = Modifier, suffix: String = "") {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (text.toIntOrNull() != value) text = value.toString()
    }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.toIntOrNull()?.let(onValue)
        },
        label = { Text(label) },
        suffix = { if (suffix.isNotEmpty()) Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

private fun trimNumber(v: Double): String =
    if (v == Math.floor(v) && kotlin.math.abs(v) < 1e9) v.toLong().toString() else v.toString()
