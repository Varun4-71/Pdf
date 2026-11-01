package com.example.batch

import android.content.Context
import android.net.Uri
import com.example.engine.ConvertRouter
import com.example.engine.FileUtils
import com.example.engine.HeaderFooterSpec
import com.example.engine.OutputFile
import com.example.engine.PageOps
import com.example.engine.PdfEngine
import com.example.engine.WatermarkSpec
import com.example.model.CompressionLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BatchOperation(val title: String, val description: String) {
    CONVERT("Convert to PDF", "Word, Excel, PowerPoint, images and text files"),
    COMPRESS("Compress PDFs", "Make PDFs smaller for sharing"),
    WATERMARK("Add watermark", "Stamp the same text on every page"),
    HEADER_FOOTER("Headers, footers, page numbers", "Same text on every page of every file"),
    ROTATE("Rotate pages", "Turn every page in every file"),
    TO_IMAGES("PDF to images", "Save each page as a picture")
}

data class BatchOptions(
    val compression: CompressionLevel = CompressionLevel.STANDARD,
    val watermark: WatermarkSpec = WatermarkSpec(text = "CONFIDENTIAL"),
    val headerFooter: HeaderFooterSpec = HeaderFooterSpec(footerCenter = "Page {page} of {total}"),
    val rotateDegrees: Int = 90,
    val imagesAsPng: Boolean = false
)

enum class JobStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class BatchJob(
    val id: Int,
    val uri: Uri,
    val name: String,
    val status: JobStatus = JobStatus.QUEUED,
    val progress: Float = 0f,
    val outputs: List<OutputFile> = emptyList(),
    val error: String? = null
)

data class BatchState(
    val operation: BatchOperation? = null,
    val jobs: List<BatchJob> = emptyList(),
    val running: Boolean = false,
    val finished: Boolean = false
) {
    val doneCount: Int get() = jobs.count { it.status == JobStatus.DONE }
    val failedCount: Int get() = jobs.count { it.status == JobStatus.FAILED }
    val overallProgress: Float
        get() {
            if (jobs.isEmpty()) return 0f
            var sum = 0f
            for (j in jobs) {
                sum += when (j.status) {
                    JobStatus.DONE, JobStatus.FAILED, JobStatus.CANCELLED -> 1f
                    JobStatus.RUNNING -> j.progress
                    JobStatus.QUEUED -> 0f
                }
            }
            return sum / jobs.size
        }
}

/**
 * Runs long jobs one file after another, outside any screen. A foreground service keeps the
 * process alive and shows progress while the person does something else.
 */
object BatchManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutable = MutableStateFlow(BatchState())
    val state: StateFlow<BatchState> = mutable.asStateFlow()
    private var worker: Job? = null

    fun start(context: Context, operation: BatchOperation, options: BatchOptions, files: List<Pair<Uri, String>>) {
        if (mutable.value.running || files.isEmpty()) return
        val jobs = ArrayList<BatchJob>()
        for ((i, f) in files.withIndex()) jobs.add(BatchJob(i, f.first, f.second))
        mutable.value = BatchState(operation, jobs, running = true, finished = false)
        val app = context.applicationContext
        BatchService.start(app)
        worker = scope.launch {
            for (job in jobs) {
                updateJob(job.id) { it.copy(status = JobStatus.RUNNING, progress = 0.03f) }
                try {
                    val outputs = process(app, operation, options, job) { p ->
                        updateJob(job.id) { it.copy(progress = p.coerceIn(0f, 0.99f)) }
                    }
                    updateJob(job.id) { it.copy(status = JobStatus.DONE, progress = 1f, outputs = outputs) }
                } catch (e: CancellationException) {
                    updateJob(job.id) { it.copy(status = JobStatus.CANCELLED) }
                    throw e
                } catch (e: Exception) {
                    updateJob(job.id) { it.copy(status = JobStatus.FAILED, error = e.localizedMessage ?: "Something went wrong") }
                }
            }
            mutable.update { it.copy(running = false, finished = true) }
        }
    }

    fun cancel() {
        worker?.cancel()
        mutable.update { st ->
            st.copy(
                running = false,
                finished = true,
                jobs = st.jobs.map {
                    if (it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING) {
                        it.copy(status = JobStatus.CANCELLED)
                    } else {
                        it
                    }
                }
            )
        }
    }

    fun clear() {
        if (!mutable.value.running) mutable.value = BatchState()
    }

    private fun updateJob(id: Int, change: (BatchJob) -> BatchJob) {
        mutable.update { st -> st.copy(jobs = st.jobs.map { if (it.id == id) change(it) else it }) }
    }

    private suspend fun process(
        context: Context,
        op: BatchOperation,
        options: BatchOptions,
        job: BatchJob,
        progress: (Float) -> Unit
    ): List<OutputFile> {
        val base = FileUtils.baseNameOf(job.name)
        PdfEngine.initialize(context)
        when (op) {
            BatchOperation.CONVERT -> {
                val kind = ConvertRouter.kindOf(job.name, context.contentResolver.getType(job.uri))
                val file = ConvertRouter.toPdf(context, job.uri, job.name, kind)
                return listOf(OutputFile(file, FileUtils.safeName(base) + ".pdf"))
            }
            BatchOperation.COMPRESS -> {
                val file = PdfEngine.compressPdf(context, job.uri, options.compression) { p, _ -> progress(p) }
                return listOf(OutputFile(file, FileUtils.safeName(base) + "_compressed.pdf"))
            }
            BatchOperation.TO_IMAGES -> {
                val format = if (options.imagesAsPng) "PNG" else "JPG"
                val images = PdfEngine.convertPdfToImages(context, job.uri, format, 2.0f) { p, _ -> progress(p) }
                val ext = if (options.imagesAsPng) "png" else "jpg"
                val outputs = ArrayList<OutputFile>()
                for ((i, f) in images.withIndex()) {
                    outputs.add(OutputFile(f, FileUtils.safeName(base) + "_page" + (i + 1) + "." + ext))
                }
                return outputs
            }
            else -> {
                val local = FileUtils.copyToCache(context, job.uri, "pdf", "batch")
                progress(0.15f)
                try {
                    val total = PageOps.pageCountOf(local)
                    if (total <= 0) throw IllegalStateException("This PDF has no readable pages.")
                    val all = (0 until total).toList()
                    val result: java.io.File
                    val suffix: String
                    when (op) {
                        BatchOperation.WATERMARK -> {
                            result = PageOps.addWatermark(context, local, all, options.watermark)
                            suffix = "_watermarked"
                        }
                        BatchOperation.HEADER_FOOTER -> {
                            result = PageOps.addHeaderFooter(context, local, all, options.headerFooter, base)
                            suffix = "_stamped"
                        }
                        else -> {
                            result = PageOps.rotatePages(context, local, all, options.rotateDegrees)
                            suffix = "_rotated"
                        }
                    }
                    progress(0.95f)
                    return listOf(OutputFile(result, FileUtils.safeName(base) + suffix + ".pdf"))
                } finally {
                    local.delete()
                }
            }
        }
    }
}
