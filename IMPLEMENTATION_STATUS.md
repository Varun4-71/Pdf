# PDFKIT – Phase 1–3 work in progress

Status of this drop: **feature code written, not yet compiled or run.**
Everything was written without a Kotlin compiler available, so expect a few compile
errors on the first GitHub Actions run. Send the failing log and they get fixed one by one.

## Progress

| Area | Code written | Built and tested |
| --- | --- | --- |
| Phase 1 – viewing and navigation | 100% | 0% |
| Phase 2 – PDF creation | 100% | 0% |
| Phase 3 – page management and transitions | 100% | 0% |
| Batch processing (background service) | 100% | 0% |
| Clean home screen and shared UI | 100% | 0% |
| Final review, unit tests, polish | 0% | – |

Overall: about 95% of the code is written, about 65% of the whole job is done
(the rest is getting it to compile and trying it on a phone).

## Where things are

Phase 1 (reader): `ui/viewer/*` (screen, pages, panels), `engine/ViewerSession.kt`,
`engine/MultiSearch.kt`, `engine/PrintHelper.kt`, `engine/AppStores.kt` (recents, bookmarks),
`ui/screens/MultiSearchScreen.kt`. Other apps can open PDFs with this app (manifest intent filter).

Phase 2 (create): `engine/DocxToPdf.kt`, `XlsxToPdf.kt`, `PptxToPdf.kt` (offline converters),
`engine/PdfCreators.kt` (text, blank, images, scans), `engine/ConvertRouter.kt`,
`ui/screens/CreateFromFilesScreen.kt`, `ScanScreen.kt`, `TextAndBlankScreens.kt`.

Phase 3 (pages): `engine/PageOps.kt` (rebuild, crop, resize, split, replace, layers, transitions),
`ui/screens/PageOrganizerScreen.kt` (drag and drop), `ui/screens/PageToolsScreen.kt`
(crop, resize, split, extract/delete, replace, background, watermark, header/footer, transitions).

Batch: `batch/BatchManager.kt`, `batch/BatchService.kt`, `ui/screens/BatchScreen.kt`.

UI: `ui/screens/HomeScreen.kt` (new), `ui/components/ToolComponents.kt` (shared pieces),
`MainActivity.kt` (routes), `AndroidManifest.xml` (service, permissions, intent filter).

No new Gradle dependencies were added.

## Known limits (by design, offline-only)

* Word, Excel and PowerPoint conversion is best effort: text, tables, pictures and basic
  formatting. Complex layouts, charts, SmartArt and unusual fonts can differ. Old .doc/.xls/.ppt
  files are not supported (save as .docx/.xlsx/.pptx first).
* Scan uses the camera and filters; there is no automatic edge detection yet.
* Removing watermarks works for ones added by this app and for standard watermark marks.
  A watermark baked into a page picture cannot be removed.
* Password-protected PDFs can be read in the viewer; page tools ask you to unlock them first.

## First things to try after it builds

1. Open a PDF, search, change layout, print one page.
2. Create from a .docx and a photo, combine them.
3. Organize pages: drag one page, rotate, save.
4. Add a watermark, then remove it.
5. Run a batch conversion with the screen off.
