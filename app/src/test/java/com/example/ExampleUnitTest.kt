package com.example

import com.example.engine.PdfEngine
import com.example.model.PdfTool
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testPdfToolsCompleteness() {
    val tools = PdfTool.values()
    assertEquals(8, tools.size)
    val toolIds = tools.map { it.id }.toSet()
    assertTrue(toolIds.contains("merge"))
    assertTrue(toolIds.contains("split"))
    assertTrue(toolIds.contains("compress"))
    assertTrue(toolIds.contains("metadata"))
    assertTrue(toolIds.contains("images_to_pdf"))
    assertTrue(toolIds.contains("pdf_to_images"))
    assertTrue(toolIds.contains("organize"))
    assertTrue(toolIds.contains("page_numbers"))
  }

  @Test
  fun testFormatFileSize() {
    assertEquals("0 B", PdfEngine.formatFileSize(0))
    assertEquals("500 B", PdfEngine.formatFileSize(500))
    assertEquals("1 KB", PdfEngine.formatFileSize(1024))
    assertEquals("1.5 MB", PdfEngine.formatFileSize((1.5 * 1024 * 1024).toLong()))
  }
}
