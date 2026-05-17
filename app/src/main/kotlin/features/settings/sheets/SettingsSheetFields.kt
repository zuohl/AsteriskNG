package features.settings.sheets

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.byValue
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.then
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import engine.network.NetworkLimits
import ui.components.StringListStatusText
import top.yukonga.miuix.kmp.basic.TextField


@Composable
internal fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    errorText: String?,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    sanitizeInput: (String) -> String = { it },
) {
    SheetTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = if (errorText == null) 12.dp else 4.dp),
        keyboardOptions = keyboardOptions,
        sanitizeInput = sanitizeInput,
    )
    errorText?.let {
        StringListStatusText(
            text = it,
            error = true,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

@Composable
internal fun SheetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    sanitizeInput: (String) -> String = { it },
) {
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestSanitizeInput by rememberUpdatedState(sanitizeInput)
    val inputTransformation = remember {
        InputTransformation
            .byValue { _, proposed -> latestSanitizeInput(proposed.toString()) }
            .then {
                latestOnValueChange(asCharSequence().toString())
            }
    }

    TextField(
        label = label,
        state = rememberTextFieldState(initialText = value),
        lineLimits = TextFieldLineLimits.SingleLine,
        inputTransformation = inputTransformation,
        keyboardOptions = keyboardOptions,
        modifier = modifier,
    )
}

internal fun sanitizeFiveDigitInput(value: String): String {
    return value.filter(Char::isDigit).take(5)
}

internal fun fiveDigitKeyboardOptions(): KeyboardOptions {
    return KeyboardOptions(
        keyboardType = KeyboardType.Number,
        imeAction = ImeAction.Done,
    )
}

internal fun isPort(value: String): Boolean {
    return value.isNotEmpty() &&
        value.all(Char::isDigit) &&
        value.toIntOrNull()?.let { it in NetworkLimits.PORT_MIN..NetworkLimits.PORT_MAX } == true
}
