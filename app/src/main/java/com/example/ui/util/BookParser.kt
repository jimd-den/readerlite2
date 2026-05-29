package com.example.ui.util

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream
import java.util.zip.ZipInputStream

object BookParser {

    fun parseEpub(inputStream: InputStream): String {
        val zipStream = ZipInputStream(inputStream)
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
                        val textOnly = stripHtml(fileContent)
                        if (textOnly.isNotBlank()) {
                            // Extract title out of name
                            val cleanChapterTitle = name.substringAfterLast("/").substringBeforeLast(".")
                                .replace("_", " ")
                                .replace("-", " ")
                                .trim()
                                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                            
                            contentBuilder.append("\n# ").append(cleanChapterTitle).append("\n\n")
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

    fun parsePdf(context: Context, inputStream: InputStream): String {
        return try {
            PDFBoxResourceLoader.init(context)
            PDDocument.load(inputStream).use { document ->
                val stripper = PDFTextStripper()
                stripper.startPage = 1
                stripper.endPage = document.numberOfPages
                stripper.getText(document)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "Error extracting text from PDF: ${e.message}"
        }
    }

    private fun stripHtml(html: String): String {
        // Replace block-level tags with newlines to preserve separation
        var text = html
            .replace("<p[^>]*>".toRegex(), "\n")
            .replace("</p>".toRegex(), "\n")
            .replace("<br[^>]*>".toRegex(), "\n")
            .replace("<h[1-6][^>]*>".toRegex(), "\n# ")
            .replace("</h[1-6]>".toRegex(), "\n")
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
        
        // Decode common HTML entities
        text = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")
            .replace("&lsquo;", "‘")
            .replace("&rsquo;", "’")
            .replace("&ndash;", "–")
            .replace("&mdash;", "—")
        
        // Clean up redundant consecutive newlines keeping max 2
        text = text.replace("\n\\s*\n\\s*\n+".toRegex(), "\n\n")
        
        return text.trim()
    }
}
