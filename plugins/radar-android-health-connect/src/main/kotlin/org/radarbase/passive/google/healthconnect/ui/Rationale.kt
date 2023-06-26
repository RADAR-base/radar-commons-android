package org.radarbase.passive.google.healthconnect.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.radarbase.passive.google.healthconnect.R
import org.radarbase.passive.google.healthconnect.ui.theme.RadarCommonsTheme

@Composable
fun Rationale(modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.health_connect_permission_rationale_title),
            modifier = modifier,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.health_connect_permission_rationale_text),
            modifier = modifier
        )
    }
}
