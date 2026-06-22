package com.atomikpanda.groundcontrol.ui.specdetail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.m3.Markdown

/** Render the complete spec body markdown (all sections, verbatim) like `mship view spec`. */
@Composable
fun SpecBodyMarkdown(body: String, modifier: Modifier = Modifier) {
    Markdown(content = body, modifier = modifier)
}
