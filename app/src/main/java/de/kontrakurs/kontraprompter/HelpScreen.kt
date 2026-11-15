package de.kontrakurs.kontraprompter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Help describes only the currently available setup; open sections do not interrupt an active session. */
@Composable
internal fun HelpScreen(onBack: () -> Unit) {
    AppPage(
        title = stringResource(R.string.help_title),
        eyebrow = stringResource(R.string.help_eyebrow),
        description = stringResource(R.string.help_description),
        onBack = onBack,
    ) {
        val spacing = LocalKontraSpacing.current
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
          Column(Modifier.padding(spacing.medium), verticalArrangement = Arrangement.spacedBy(spacing.small)) {
            Text(stringResource(R.string.help_quick_start), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            HelpParagraph(stringResource(R.string.help_paragraph_01))
            HelpParagraph(stringResource(R.string.help_paragraph_02))
            HelpParagraph(stringResource(R.string.help_paragraph_03))
          }
        }
        HelpSection(stringResource(R.string.help_section_01)) {
            HelpParagraph(stringResource(R.string.help_paragraph_04))
            HelpParagraph(stringResource(R.string.help_paragraph_05))
            HelpParagraph(stringResource(R.string.help_paragraph_06))
        }
        HelpSection(stringResource(R.string.help_section_02)) {
            HelpParagraph(stringResource(R.string.help_paragraph_07))
            HelpParagraph(stringResource(R.string.help_paragraph_08))
            HelpParagraph(stringResource(R.string.help_paragraph_09))
        }
        HelpSection(stringResource(R.string.help_section_03)) {
            HelpParagraph(stringResource(R.string.help_paragraph_10))
            HelpParagraph(stringResource(R.string.help_paragraph_11))
            HelpParagraph(stringResource(R.string.help_paragraph_12))
        }
        HelpSection(stringResource(R.string.help_section_04)) {
            HelpParagraph(stringResource(R.string.help_paragraph_13))
            HelpParagraph(stringResource(R.string.help_paragraph_14))
            HelpParagraph(stringResource(R.string.help_paragraph_15))
        }
        HelpSection(stringResource(R.string.help_section_05)) {
            HelpParagraph(stringResource(R.string.help_paragraph_16))
            HelpParagraph(stringResource(R.string.help_paragraph_17))
            HelpParagraph(stringResource(R.string.help_paragraph_18))
            HelpParagraph(stringResource(R.string.help_paragraph_19))
            HelpParagraph(stringResource(R.string.help_paragraph_20))
        }
        HelpSection(stringResource(R.string.help_section_06)) {
            HelpParagraph(stringResource(R.string.help_paragraph_21))
            HelpParagraph(stringResource(R.string.help_paragraph_22))
            HelpParagraph(stringResource(R.string.help_paragraph_23))
        }
    }
}

@Composable
private fun HelpSection(title: String, content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val spacing = LocalKontraSpacing.current
    val expandedLabel = stringResource(if (expanded) R.string.help_expanded else R.string.help_collapsed)
    Column {
        ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            trailingContent = { Text(if (expanded) "−" else "+", style = MaterialTheme.typography.titleLarge) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().heightIn(min = spacing.touchTarget)
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = expandedLabel },
        )
        if (expanded) Column(
            Modifier.fillMaxWidth().padding(horizontal = spacing.medium, vertical = spacing.small),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) { content() }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun HelpParagraph(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
}

@Preview(name = "Hilfe dunkel", widthDp = 360, heightDp = 780, showBackground = true)
@Preview(name = "Hilfe 200 %", widthDp = 320, heightDp = 640, fontScale = 2f, showBackground = true)
@Preview(name = "Hilfe breit", widthDp = 840, heightDp = 600, showBackground = true)
@Composable
private fun HelpScreenPreview() {
    KontraTheme { Surface(color = MaterialTheme.colorScheme.background) { HelpScreen(onBack = {}) } }
}

@Preview(name = "Hilfe hell", widthDp = 360, heightDp = 780, showBackground = true)
@Composable
private fun HelpScreenLightPreview() {
    KontraAdaptiveTheme(darkTheme = false, dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) { HelpScreen(onBack = {}) }
    }
}
