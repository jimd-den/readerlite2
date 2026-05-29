package com.example.ui.util

import android.content.Context
import android.util.Log
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

object BookParser {
    private const val TAG = "BookParser"

    fun parseEpub(inputStream: InputStream): String {
        val byteArr = try {
            inputStream.readBytes()
        } catch (e: Exception) {
            e.printStackTrace()
            return "Error reading EPUB bytes"
        }

        // 1. First Pass: Parse NCX (TOC) to identify chapters vs subheadings
        val ncxChapters = mutableSetOf<String>()
        val ncxSubheadings = mutableSetOf<String>()

        try {
            val zipStream = ZipInputStream(byteArr.inputStream())
            var entry = zipStream.nextEntry
            while (entry != null) {
                val name = entry.name.lowercase()
                if (name.endsWith(".ncx")) {
                    val ncxXml = zipStream.bufferedReader(Charsets.UTF_8).readText()
                    parseNcx(ncxXml, ncxChapters, ncxSubheadings)
                    Log.d(TAG, "Parsed NCX table of contents. Chapters: ${ncxChapters.size}, Subheadings: ${ncxSubheadings.size}")
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in first-pass NCX parsing", e)
        }

        // 2. Second Pass: Read document contents
        val zipStream = ZipInputStream(byteArr.inputStream())
        val contentBuilder = StringBuilder()
        
        try {
            var entry = zipStream.nextEntry
            while (entry != null) {
                val name = entry.name.lowercase()
                // Read text content files which end with xhtml / html / htm (excluding images etc.)
                if (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".xml")) {
                    // Skip containers, metadata and style files
                    if (!name.contains("container.xml") && !name.contains("toc.ncx") && !name.contains("content.opf") && !name.contains("style")) {
                        val fileContent = zipStream.bufferedReader(Charsets.UTF_8).readText()
                        val textOnly = stripHtml(fileContent, ncxChapters, ncxSubheadings)
                        if (textOnly.isNotBlank()) {
                            // Extract title out of name
                            val cleanChapterTitle = name.substringAfterLast("/").substringBeforeLast(".")
                                .replace("_", " ")
                                .replace("-", " ")
                                .trim()
                                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                            
                            // If chapter title is not already inside content as a primary header, append it
                            if (!textOnly.startsWith("# ")) {
                                contentBuilder.append("\n# ").append(cleanChapterTitle).append("\n\n")
                            }
                            contentBuilder.append(textOnly).append("\n")
                        }
                    }
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return "Error parsing EPUB: ${e.message}"
        } finally {
            try {
                zipStream.close()
            } catch (ex: Exception) {
                // Ignore
            }
        }
        return contentBuilder.toString()
    }

    private fun parseNcx(xmlContent: String, chapters: MutableSet<String>, subheadings: MutableSet<String>) {
        try {
            val parser = Xml.newPullParser()
            parser.setInput(xmlContent.reader())
            var eventType = parser.eventType
            var currentNavPointDepth = 0
            var inLabelText = false
            val textBuilder = StringBuilder()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (name == "navPoint") {
                            currentNavPointDepth++
                        } else if (name == "text") {
                            inLabelText = true
                            textBuilder.setLength(0)
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inLabelText) {
                            textBuilder.append(parser.text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (name == "navPoint") {
                            currentNavPointDepth--
                        } else if (name == "text") {
                            inLabelText = false
                            val cleanTitle = textBuilder.toString().trim()
                            if (cleanTitle.isNotEmpty()) {
                                if (currentNavPointDepth == 1) {
                                    chapters.add(cleanTitle)
                                } else if (currentNavPointDepth > 1) {
                                    subheadings.add(cleanTitle)
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "NCX PullParser exception: ", e)
        }
    }

    fun parsePdf(context: Context, inputStream: InputStream): String {
        return try {
            PDFBoxResourceLoader.init(context)
            PDDocument.load(inputStream).use { document ->
                val pdfChapters = mutableSetOf<String>()
                val pdfSubheadings = mutableSetOf<String>()
                extractPdfOutline(document, pdfChapters, pdfSubheadings)
                Log.d(TAG, "Parsed PDF outline bookmarks. Chapters: ${pdfChapters.size}, Subheadings: ${pdfSubheadings.size}")

                val stripper = PDFTextStripper()
                stripper.startPage = 1
                stripper.endPage = document.numberOfPages
                val rawText = stripper.getText(document)

                if (pdfChapters.isEmpty() && pdfSubheadings.isEmpty()) {
                    rawText
                } else {
                    val lines = rawText.split("\n")
                    val formatted = StringBuilder()
                    val chNormalized = pdfChapters.map { it.lowercase().trim() }.toSet()
                    val subNormalized = pdfSubheadings.map { it.lowercase().trim() }.toSet()

                    for (line in lines) {
                        val trimmed = line.trim()
                        val lowerTrimmed = trimmed.lowercase()
                        if (trimmed.isNotEmpty()) {
                            when {
                                chNormalized.contains(lowerTrimmed) -> {
                                    formatted.append("\n# ").append(trimmed).append("\n")
                                }
                                subNormalized.contains(lowerTrimmed) -> {
                                    formatted.append("\n## ").append(trimmed).append("\n")
                                }
                                else -> {
                                    formatted.append(line).append("\n")
                                }
                            }
                        } else {
                            formatted.append("\n")
                        }
                    }
                    formatted.toString()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "Error extracting text from PDF: ${e.message}"
        }
    }

    private fun extractPdfOutline(document: PDDocument, pdfChapters: MutableSet<String>, pdfSubheadings: MutableSet<String>) {
        try {
            val outline = document.documentCatalog.documentOutline ?: return
            var currentItem = outline.firstChild
            while (currentItem != null) {
                val title = currentItem.title?.trim()
                if (!title.isNullOrEmpty()) {
                    pdfChapters.add(title)
                }
                
                // Nested level bookmarks as subheadings
                var childItem = currentItem.firstChild
                while (childItem != null) {
                    val subTitle = childItem.title?.trim()
                    if (!subTitle.isNullOrEmpty()) {
                        pdfSubheadings.add(subTitle)
                    }
                    childItem = childItem.nextSibling
                }
                currentItem = currentItem.nextSibling
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error seeking PDF outline node hierarchy", e)
        }
    }

    private fun stripHtml(html: String, chapters: Set<String>, subheadings: Set<String>): String {
        // Pattern to match any header tags <h1...6> and parse them structurally
        val headingRegex = Regex("<(h[1-6])\\b[^>]*>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
        var text = html
        
        text = headingRegex.replace(text) { matchResult ->
            val tag = matchResult.groups[1]?.value?.lowercase() ?: "h1"
            val body = matchResult.groups[2]?.value ?: ""
            val cleanBody = body.replace("<[^>]*>".toRegex(), "").trim()
            
            val level = when {
                chapters.any { it.equals(cleanBody, ignoreCase = true) } -> 1
                subheadings.any { it.equals(cleanBody, ignoreCase = true) } -> 2
                tag == "h1" || tag == "h2" -> 1
                else -> 2
            }
            
            if (level == 1) {
                "\n# $cleanBody\n"
            } else {
                "\n## $cleanBody\n"
            }
        }

        // Replace block-level tags with newlines
        text = text
            .replace("<p[^>]*>".toRegex(), "\n")
            .replace("</p>".toRegex(), "\n")
            .replace("<br[^>]*>".toRegex(), "\n")
            .replace("<div[^>]*>".toRegex(), "\n")
            .replace("</div>".toRegex(), "\n")
            .replace("<li[^>]*>".toRegex(), "\n• ")
            .replace("</li>".toRegex(), "\n")
        
        // Strip style sheets if present
        text = text.replace("<style[^>]*>[\\s\\S]*?</style>".toRegex(), "")
        // Strip head block
        text = text.replace("<head[^>]*>[\\s\\S]*?</head>".toRegex(), "")
        // Strip scripts
        text = text.replace("<script[^>]*>[\\s\\S]*?</script>".toRegex(), "")
        
        // Strip all remaining tags
        text = text.replace("<[^>]*>".toRegex(), "")
        
        // Decode common and numeric HTML entities
        text = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            } else {
                @Suppress("DEPRECATION")
                android.text.Html.fromHtml(text).toString()
            }
        } catch (e: Exception) {
            text
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&#8212;", "—")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&ldquo;", "“")
                .replace("&rdquo;", "”")
                .replace("&lsquo;", "‘")
                .replace("&rsquo;", "’")
        }
        
        // Clean up redundant consecutive newlines keeping max 2
        text = text.replace("\n\\s*\n\\s*\n+".toRegex(), "\n\n")
        
        return text.trim()
    }
}
