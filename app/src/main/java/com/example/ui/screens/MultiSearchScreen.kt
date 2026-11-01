package com.example.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.MultiSearch
import com.example.engine.MultiSearchEvent
import com.example.engine.PdfEngine
import com.example.engine.RecentFiles
import com.example.engine.ViewerLaunch
import com.example.ui.components.AppTextField
import com.example.ui.components.FileRow
import com.example.ui.components.HelperText
import com.example.ui.components.PrimaryButton
import com.example.ui.components.SectionCard
import com.example.ui.components.ToolScreenFrame
import com.example.ui.components.rememberFilesPicker
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class HitRow(val page: Int, val snippet: String)

private class FileResult(val uri: Uri, startName: String) {
    var name by mutableStateOf(startName)
    var status by mutableStateOf("Waiting")
    val hits = mutableStateListOf<HitRow>()
}

/** Searches the text of several PDFs at once and opens a result at the right page. */
@Composable
fun MultiSearchScreen(onBack: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember { mutableStateListOf<FileResult>() }
    var query by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    val pick = rememberFilesPicker(arrayOf("application/pdf")) { uris ->
        scope.launch {
            for (u in uris) {
                if (files.none { it.uri == u }) {
                    val name = withContext(Dispatchers.IO) { PdfEngine.getFileName(context, u) }
                    files.add(FileResult(u, name))
                }
            }
        }
    }

    fun addRecent() {
        for (item in RecentFiles.list(context)) {
            val u = Uri.parse(item.uri)
            if (files.none { it.uri == u }) files.add(FileResult(u, item.name))
        }
    }

    fun startSearch() {
        val q = query.trim()
        if (q.length < 2 || files.isEmpty()) return
        job?.cancel()
        for (f in files) {
            f.hits.clear()
            f.status = "Waiting"
        }
        searched = q
        running = true
        job = scope.launch {
            try {
                MultiSearch.searchFiles(context, files.map { it.uri }, q).collect { event ->
                    when (event) {
                        is MultiSearchEvent.FileStarted -> {
                            files.getOrNull(event.index)?.status = "Searching"
                        }
                        is MultiSearchEvent.Hit -> {
                            files.getOrNull(event.fileIndex)?.hits?.add(HitRow(event.page, event.snippet))
                        }
                        is MultiSearchEvent.FileDone -> {
                            files.getOrNull(event.index)?.status = if (event.hits == 0) {
                                "No matches"
                            } else {
                                event.hits.toString() + (if (event.hits == 1) " match" else " matches") + " in " + event.pages + " pages"
                            }
                        }
                        is MultiSearchEvent.FileFailed -> {
                            files.getOrNull(event.index)?.status = event.message
                        }
                    }
                }
            } finally {
                running = false
            }
        }
    }

    ToolScreenFrame(
        title = "Search in PDFs",
        subtitle = "Find a word or phrase across several files",
        onBack = onBack
    ) {
        item {
            SectionCard(title = "PDFs to search") {
                if (files.isEmpty()) {
                    HelperText("Add the PDFs you want to look through.")
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    files.forEachIndexed { index, f ->
                        FileRow(
                            name = f.name,
                            detail = f.status,
                            icon = Icons.Default.PictureAsPdf,
                            onRemove = { files.removeAt(index) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    PrimaryButton(
                        text = "Add PDFs",
                        icon = Icons.Default.Add,
                        isPrimary = false,
                        modifier = Modifier.weight(1f),
                        onClick = { pick() }
                    )
                    PrimaryButton(
                        text = "Recent",
                        icon = Icons.Default.History,
                        isPrimary = false,
                        modifier = Modifier.weight(1f),
                        onClick = { addRecent() }
                    )
                }
            }
        }
        item {
            SectionCard(title = "Search for") {
                AppTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "Word or phrase",
                    placeholder = "At least 2 letters"
                )
                PrimaryButton(
                    text = if (running) "Stop" else "Search",
                    icon = Icons.Default.Search,
                    enabled = running || (query.trim().length >= 2 && files.isNotEmpty()),
                    onClick = {
                        if (running) {
                            job?.cancel()
                            running = false
                        } else {
                            startSearch()
                        }
                    }
                )
                HelperText("Scanned pages that are only pictures have no text to search.")
            }
        }
        for (f in files) {
            if (f.hits.isNotEmpty()) {
                item(key = "head_" + f.uri.toString()) {
                    Text(
                        text = f.name,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                itemsIndexed(f.hits, key = { i, _ -> f.uri.toString() + "#" + i }) { _, hit ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x14FFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .clickable {
                                ViewerLaunch.open(f.uri, hit.page, searched)
                                onOpen()
                            }
                            .padding(12.dp)
                    ) {
                        Text(text = "Page " + (hit.page + 1), color = CyanAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(text = hit.snippet, color = TextSecondary, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item {
            if (!running && searched.isNotEmpty() && files.all { it.hits.isEmpty() }) {
                Text(text = "No matches for \"" + searched + "\".", color = TextMuted, fontSize = 14.sp)
            }
        }
    }
}
