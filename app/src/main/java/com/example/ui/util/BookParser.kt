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

        val zipMap = mutableMapOf<String, ByteArray>()
        try {
            val zipStream = ZipInputStream(byteArr.inputStream())
            var entry = zipStream.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    zipMap[entry.name] = zipStream.readBytes()
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
            zipStream.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading EPUB zip in memory", e)
            return "Error reading EPUB contents"
        }

        if (zipMap.isEmpty()) {
            return "Empty EPUB file (no contents)"
        }

        // Normalize zip map keys to allow easy lookup
        val normalizedZipMap = zipMap.mapKeys { (key, _) ->
            key.lowercase().replace('\\', '/').replace("//", "/").trimStart('/')
        }

        // Find the OPF file (metadata defining packaging and reading layout)
        val opfEntry = normalizedZipMap.entries.find { it.key.endsWith(".opf") }
        val contentBuilder = StringBuilder()

        var opfParsedSuccessfully = false

        if (opfEntry != null) {
            val opfPath = opfEntry.key
            val opfFolderPrefix = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""
            val opfXmlStr = String(opfEntry.value, Charsets.UTF_8)
            
            // Extract manifest catalog and spine sequential order
            val manifest = mutableMapOf<String, String>() // id -> href xml ref
            val spine = mutableListOf<String>() // idref ordered list

            try {
                val parser = Xml.newPullParser()
                parser.setInput(opfXmlStr.reader())
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    val rawName = parser.name
                    if (rawName != null) {
                        val name = rawName.lowercase()
                        if (eventType == XmlPullParser.START_TAG) {
                            if (name == "item" || name.endsWith(":item")) {
                                var id = ""
                                var href = ""
                                for (i in 0 until parser.attributeCount) {
                                    val attrName = parser.getAttributeName(i).lowercase()
                                    if (attrName == "id") {
                                        id = parser.getAttributeValue(i) ?: ""
                                    } else if (attrName == "href") {
                                        href = parser.getAttributeValue(i) ?: ""
                                    }
                                }
                                if (id.isNotEmpty() && href.isNotEmpty()) {
                                    manifest[id] = href
                                }
                            } else if (name == "itemref" || name.endsWith(":itemref")) {
                                var idref = ""
                                for (i in 0 until parser.attributeCount) {
                                    if (parser.getAttributeName(i).lowercase() == "idref") {
                                        idref = parser.getAttributeValue(i) ?: ""
                                        break
                                    }
                                }
                                if (idref.isNotEmpty()) {
                                    spine.add(idref)
                                }
                            }
                        }
                    }
                    eventType = parser.next()
                }
                Log.d(TAG, "Parsed OPF xml metadata success. Spine elements: ${spine.size}")
            } catch (e: Exception) {
                Log.e(TAG, "Error in OPF reader xml iteration parsing", e)
            }

            // Parse NCX Table of Contents files for pristine chapter mapping
            val ncxChapters = mutableSetOf<String>()
            val ncxSubheadings = mutableSetOf<String>()
            val fileToChapterMap = mutableMapOf<String, String>()

            val ncxEntry = normalizedZipMap.entries.find { it.key.endsWith(".ncx") }
            if (ncxEntry != null) {
                try {
                    val ncxXml = String(ncxEntry.value, Charsets.UTF_8)
                    parseNcx(ncxXml, ncxChapters, ncxSubheadings, fileToChapterMap)
                    Log.d(TAG, "Parsed NCX TOC. Mapped: ${fileToChapterMap.size}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading NCX file", e)
                }
            }

            // Extract each spine resource content file sequentially in linear reading sequence!
            if (spine.isNotEmpty()) {
                var contentsSuccessfullyTranscribed = 0
                for (idref in spine) {
                    val relativeHref = manifest[idref] ?: continue
                    
                    // Construct and normalize absolute path inside zip resource file system
                    val entryPath = (opfFolderPrefix + relativeHref).lowercase()
                        .replace('\\', '/').replace("//", "/").trimStart('/')
                    
                    val fileBytes = normalizedZipMap[entryPath] ?: continue
                    val fileContent = String(fileBytes, Charsets.UTF_8)
                    val textOnly = stripHtml(fileContent, ncxChapters, ncxSubheadings)
                    
                    if (textOnly.isNotBlank()) {
                        val simpleName = entryPath.substringAfterLast("/").lowercase().trim()
                        
                        // Use accurate NCX title mapped filename or beautifully formatted title
                        val cleanChapterTitle = fileToChapterMap[simpleName] ?: entryPath.substringAfterLast("/").substringBeforeLast(".")
                            .replace("_", " ")
                            .replace("-", " ")
                            .trim()
                            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

                        // Check if the stripped content already starts with a structural header to avoid repeats
                        val alreadyHasHeaderPrefix = textOnly.startsWith("# ") || textOnly.startsWith("## ")
                        
                        if (!alreadyHasHeaderPrefix) {
                            contentBuilder.append("\n# ").append(cleanChapterTitle).append("\n\n")
                        }
                        contentBuilder.append(textOnly).append("\n")
                        contentsSuccessfullyTranscribed++
                    }
                }
                if (contentsSuccessfullyTranscribed > 0) {
                    opfParsedSuccessfully = true
                }
            }
        }

        // Alphabetical markup content fallback if OPF parsing failed or spine content is empty
        if (!opfParsedSuccessfully) {
            Log.w(TAG, "Fallback to alphabetical physical parsing.")
            val textBlocks = normalizedZipMap.entries
                .filter { (key, _) ->
                    (key.endsWith(".xhtml") || key.endsWith(".html") || key.endsWith(".htm") || key.endsWith(".xml")) &&
                    !key.contains("container.xml") && !key.contains("toc.ncx") && !key.contains("content.opf") && !key.contains("style")
                }
                .sortedBy { it.key }

            val genericChapters = mutableSetOf<String>()
            val genericSubheadings = mutableSetOf<String>()

            for ((key, byteData) in textBlocks) {
                val fileContent = String(byteData, Charsets.UTF_8)
                val textOnly = stripHtml(fileContent, genericChapters, genericSubheadings)
                if (textOnly.isNotBlank()) {
                    val cleanChapterTitle = key.substringAfterLast("/").substringBeforeLast(".")
                        .replace("_", " ")
                        .replace("-", " ")
                        .trim()
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

                    if (!textOnly.startsWith("# ") && !textOnly.startsWith("## ")) {
                        contentBuilder.append("\n# ").append(cleanChapterTitle).append("\n\n")
                    }
                    contentBuilder.append(textOnly).append("\n")
                }
            }
        }

        return contentBuilder.toString()
    }

    private fun parseNcx(
        xmlContent: String, 
        chapters: MutableSet<String>, 
        subheadings: MutableSet<String>,
        fileToChapterMap: MutableMap<String, String>
    ) {
        try {
            val parser = Xml.newPullParser()
            parser.setInput(xmlContent.reader())
            var eventType = parser.eventType
            
            var currentNavPointDepth = 0
            var inLabelText = false
            val textBuilder = StringBuilder()

            // Depth tracking stacks to avoid nested navPoint override issues
            val depthTitles = Array(32) { "" }
            val depthSrcs = Array(32) { "" }

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val rawName = parser.name
                val name = rawName?.lowercase()
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (name == "navpoint") {
                            currentNavPointDepth++
                            if (currentNavPointDepth < 32) {
                                depthTitles[currentNavPointDepth] = ""
                                depthSrcs[currentNavPointDepth] = ""
                            }
                        } else if (name == "text") {
                            inLabelText = true
                            textBuilder.setLength(0)
                        } else if (name == "content") {
                            var srcVal = ""
                            for (i in 0 until parser.attributeCount) {
                                if (parser.getAttributeName(i).lowercase() == "src") {
                                    srcVal = parser.getAttributeValue(i) ?: ""
                                    break
                                }
                            }
                            if (currentNavPointDepth < 32) {
                                depthSrcs[currentNavPointDepth] = srcVal
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inLabelText) {
                            textBuilder.append(parser.text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (name == "text") {
                            inLabelText = false
                            if (currentNavPointDepth < 32) {
                                depthTitles[currentNavPointDepth] = textBuilder.toString().trim()
                            }
                        } else if (name == "navpoint") {
                            if (currentNavPointDepth < 32) {
                                val title = depthTitles[currentNavPointDepth]
                                val src = depthSrcs[currentNavPointDepth]
                                if (title.isNotEmpty()) {
                                    if (currentNavPointDepth == 1) {
                                        chapters.add(title)
                                        if (src.isNotEmpty()) {
                                            val cleanFile = src.substringBefore("#").substringAfterLast("/").lowercase().trim()
                                            if (cleanFile.isNotEmpty()) {
                                                fileToChapterMap[cleanFile] = title
                                            }
                                        }
                                    } else {
                                        subheadings.add(title)
                                    }
                                }
                            }
                            currentNavPointDepth--
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
        
        // Clean up soft hyphens and other word-splitting artifacts that fragment individual words
        text = text
            .replace("\u00ad", "")  // Soft hyphen
            .replace("\u200b", "")  // Zero-width space
            .replace("\u200c", "")  // Zero-width non-joiner
            .replace("\u200d", "")  // Zero-width joiner
            .replace("&shy;", "")   // Entity soft-hyphen
            .replace("&#173;", "")  // Numeric soft-hyphen

        // Clean up redundant consecutive newlines keeping max 2
        text = text.replace("\n\\s*\n\\s*\n+".toRegex(), "\n\n")
        
        return text.trim()
    }
}
