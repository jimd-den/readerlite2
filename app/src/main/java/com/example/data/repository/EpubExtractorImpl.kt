package com.example.data.repository

import android.util.Log
import android.util.Xml
import com.example.domain.model.EpubStructureDomainModel
import com.example.domain.model.ParsedChapterDomain
import com.example.domain.model.ParsedSentenceDomain
import com.example.domain.repository.EpubExtractor
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

class EpubExtractorImpl : EpubExtractor {
    private val TAG = "EpubExtractorImpl"

    data class TempTocItem(
        val title: String,
        val zipPath: String,
        val fragment: String,
        val originalHref: String,
        val isSubchapter: Boolean = false,
        val parentTitle: String? = null
    )

    override fun parseEpub(inputStream: InputStream): EpubStructureDomainModel {
        val byteArr = try {
            inputStream.readBytes()
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyStructure()
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
            return emptyStructure()
        }

        if (zipMap.isEmpty()) return emptyStructure()

        val normalizedZipMap = zipMap.mapKeys { (key, _) ->
            key.lowercase().replace('\\', '/').replace("//", "/").trimStart('/')
        }

        // Formal container XML search to find the OPF path
        var opfPath = findXmlPackagePath(normalizedZipMap)
        if (opfPath == null) {
            // Fallback for non-compliant/loose packaging
            val opfEntry = normalizedZipMap.entries.find { it.key.endsWith(".opf") }
            opfPath = opfEntry?.key
        }

        var epubVersion = "2.0"
        val manifest = mutableMapOf<String, String>()
        val spine = mutableListOf<String>()
        var navHref: String? = null
        var tocNcxHref: String? = null
        var opfFolderPrefix = ""
        var extractedTitle: String? = null
        var extractedAuthor: String? = null

        if (opfPath != null) {
            opfFolderPrefix = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""
            val opfBytes = normalizedZipMap[opfPath]
            if (opfBytes != null) {
                val opfXmlStr = String(opfBytes, Charsets.UTF_8)
                try {
                    val parser = Xml.newPullParser()
                    parser.setInput(opfXmlStr.reader())
                    var eventType = parser.eventType
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        val rawName = parser.name
                        val name = rawName?.lowercase()
                        if (eventType == XmlPullParser.START_TAG) {
                            if (name == "package" || name?.endsWith(":package") == true) {
                                for (i in 0 until parser.attributeCount) {
                                    if (parser.getAttributeName(i).lowercase() == "version") {
                                        epubVersion = parser.getAttributeValue(i) ?: "2.0"
                                        break
                                    }
                                }
                            } else if (name == "title" || name?.endsWith(":title") == true) {
                                extractedTitle = parser.nextText()
                            } else if (name == "creator" || name?.endsWith(":creator") == true) {
                                extractedAuthor = parser.nextText()
                            } else if (name == "item" || name?.endsWith(":item") == true) {
                                var id = ""
                                var href = ""
                                var properties = ""
                                for (i in 0 until parser.attributeCount) {
                                    val attrName = parser.getAttributeName(i).lowercase()
                                    if (attrName == "id") {
                                        id = parser.getAttributeValue(i) ?: ""
                                    } else if (attrName == "href") {
                                        href = parser.getAttributeValue(i) ?: ""
                                    } else if (attrName == "properties") {
                                        properties = parser.getAttributeValue(i) ?: ""
                                    }
                                }
                                if (id.isNotEmpty() && href.isNotEmpty()) {
                                    manifest[id.lowercase().trim()] = href.trim()
                                    if (properties == "nav" || properties.contains("nav")) {
                                        navHref = href
                                    }
                                    if (href.endsWith(".ncx")) {
                                        tocNcxHref = href
                                    }
                                }
                            } else if (name == "itemref" || name?.endsWith(":itemref") == true) {
                                var idref = ""
                                for (i in 0 until parser.attributeCount) {
                                    if (parser.getAttributeName(i).lowercase() == "idref") {
                                        idref = parser.getAttributeValue(i) ?: ""
                                        break
                                    }
                                }
                                if (idref.isNotEmpty()) {
                                    spine.add(idref.lowercase().trim())
                                }
                            }
                        }
                        eventType = parser.next()
                    }
                    Log.d(TAG, "Parsed OPF xml. Title: $extractedTitle, Author: $extractedAuthor, Version: $epubVersion, Spine: ${spine.size}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing OPF metadata", e)
                }
            }
        }

        val unifiedTocList = mutableListOf<TempTocItem>()
        var parsedViaEpub3Nav = false
        val resolvedNavHref = navHref ?: manifest.values.find { it.contains("nav.xhtml") || it.contains("toc.xhtml") }
        if (opfPath != null && resolvedNavHref != null) {
            val navPathInZip = (opfFolderPrefix + resolvedNavHref).lowercase()
                .replace('\\', '/').replace("//", "/").trimStart('/')
            val navBytes = normalizedZipMap[navPathInZip]
            if (navBytes != null) {
                val navXmlStr = String(navBytes, Charsets.UTF_8)
                val items = parseEpub3Nav(navXmlStr, navPathInZip)
                if (items.isNotEmpty()) {
                    unifiedTocList.addAll(items)
                    parsedViaEpub3Nav = true
                    Log.d(TAG, "Loaded TOC from EPUB3 Nav document: ${items.size} items")
                }
            }
        }

        if (opfPath != null && !parsedViaEpub3Nav) {
            val resolvedNcxHref = tocNcxHref ?: manifest.values.find { it.endsWith(".ncx") }
            if (resolvedNcxHref != null) {
                val ncxPathInZip = (opfFolderPrefix + resolvedNcxHref).lowercase()
                    .replace('\\', '/').replace("//", "/").trimStart('/')
                val ncxBytes = normalizedZipMap[ncxPathInZip]
                if (ncxBytes != null) {
                    val ncxXmlStr = String(ncxBytes, Charsets.UTF_8)
                    val items = parseEpub2Ncx(ncxXmlStr, ncxPathInZip)
                    if (items.isNotEmpty()) {
                        unifiedTocList.addAll(items)
                        Log.d(TAG, "Loaded TOC from EPUB2 NCX: ${items.size} items")
                    }
                }
            }
        }

        if (unifiedTocList.isEmpty()) {
            val fallbackNcxEntry = normalizedZipMap.entries.find { it.key.endsWith(".ncx") }
            if (fallbackNcxEntry != null) {
                try {
                    val ncxXmlStr = String(fallbackNcxEntry.value, Charsets.UTF_8)
                    val items = parseEpub2Ncx(ncxXmlStr, fallbackNcxEntry.key)
                    unifiedTocList.addAll(items)
                    Log.d(TAG, "Loaded fallback NCX file from ${fallbackNcxEntry.key}: ${items.size} items")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in nested NCX fallback parsing", e)
                }
            }
        }

        val chaptersList = mutableListOf<ParsedChapterDomain>()
        val sentencesList = mutableListOf<ParsedSentenceDomain>()

        for (idref in spine) {
            val relativeHref = manifest[idref] ?: continue
            val entryPath = (opfFolderPrefix + relativeHref).lowercase()
                .replace('\\', '/').replace("//", "/").trimStart('/')
            
            val fileBytes = normalizedZipMap[entryPath]
            if (fileBytes != null) {
                val fileContent = String(fileBytes, Charsets.UTF_8)
                val fileTocItems = unifiedTocList.filter { it.zipPath == entryPath }
                
                val fileChapters = if (fileTocItems.isNotEmpty()) {
                    fileTocItems.map { item ->
                        ParsedChapterDomain(item.title, item.isSubchapter, item.parentTitle)
                    }
                } else {
                    val fallbackTitle = entryPath.substringAfterLast("/").substringBeforeLast(".")
                        .replace("_", " ")
                        .replace("-", " ")
                        .trim()
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    listOf(ParsedChapterDomain(fallbackTitle, false, null))
                }

                val allChaptersSet = fileChapters.filter { !it.isSubchapter }.map { cleanForMatch(it.title) }.toSet()
                val allSubheadingsSet = fileChapters.filter { it.isSubchapter }.map { cleanForMatch(it.title) }.toSet()

                val textOnly = stripHtml(fileContent, allChaptersSet, allSubheadingsSet)
                if (textOnly.isNotBlank()) {
                    val activeChapterIndexStart = chaptersList.size
                    chaptersList.addAll(fileChapters)

                    val lines = textOnly.split("\n")
                    var currentActiveSubIndex = 0
                    var sentenceCount = 0

                    for (line in lines) {
                        val lineTrimmed = line.trim()
                        if (lineTrimmed.isEmpty()) continue

                        val cleanLine = cleanForMatch(lineTrimmed)
                        var shifted = false
                        for (nextIdx in (currentActiveSubIndex + 1) until fileChapters.size) {
                            if (cleanForMatch(fileChapters[nextIdx].title) == cleanLine) {
                                currentActiveSubIndex = nextIdx
                                shifted = true
                                break
                            }
                        }

                        if (shifted || cleanLine == cleanForMatch(fileChapters[currentActiveSubIndex].title)) {
                            continue
                        }

                        val sentences = lineTrimmed.split(Regex("(?<=[.!?])\\s+"))
                        val activeGlChapterIndex = activeChapterIndexStart + currentActiveSubIndex
                        val sectionTitle = fileChapters[currentActiveSubIndex].title

                        for (sent in sentences) {
                            val sText = sent.trim()
                            if (sText.isNotEmpty()) {
                                sentencesList.add(
                                    ParsedSentenceDomain(
                                        chapterIndex = activeGlChapterIndex,
                                        sentenceIndex = sentenceCount,
                                        text = sText,
                                        sectionTitle = sectionTitle
                                    )
                                )
                                sentenceCount++
                            }
                        }
                    }
                }
            }
        }

        return EpubStructureDomainModel(
            title = extractedTitle,
            author = extractedAuthor,
            chapters = chaptersList,
            sentences = sentencesList
        )
    }

    private fun findXmlPackagePath(zipMap: Map<String, ByteArray>): String? {
        val containerFile = zipMap["meta-inf/container.xml"] ?: return null
        try {
            val parser = Xml.newPullParser()
            parser.setInput(String(containerFile, Charsets.UTF_8).reader())
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name?.lowercase() == "rootfile") {
                    for (i in 0 until parser.attributeCount) {
                        if (parser.getAttributeName(i).lowercase() == "full-path") {
                            return parser.getAttributeValue(i)?.trim()
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error seeking package OPF path in container.xml", e)
        }
        return null
    }

    private fun parseEpub3Nav(navXml: String, navPath: String): List<TempTocItem> {
        val items = mutableListOf<TempTocItem>()
        class NavPointNode(
            var title: String = "",
            var href: String = "",
            val children: MutableList<NavPointNode> = mutableListOf(),
            var parent: NavPointNode? = null
        )

        val rootNode = NavPointNode()
        var contentToParse = navXml
        
        try {
            val navRegex = Regex("<nav[^>]*epub:type=[\"']toc[\"'][^>]*>(.*?)</nav>", RegexOption.DOT_MATCHES_ALL)
            var navMatch = navRegex.find(navXml)
            if (navMatch == null) {
                val looseNavRegex = Regex("<nav[^>]*>(.*?)</nav>", RegexOption.DOT_MATCHES_ALL)
                navMatch = looseNavRegex.find(navXml)
            }
            contentToParse = navMatch?.value ?: navXml

            val parser = Xml.newPullParser()
            parser.setInput(contentToParse.reader())
            var eventType = parser.eventType

            var currentOlDepth = 0
            val activeNodesByDepth = Array<NavPointNode?>(32) { null }
            var inAnchorText = false
            val textBuilder = StringBuilder()
            var currentHref = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val rawName = parser.name
                val name = rawName?.lowercase()
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (name == "ol") {
                            currentOlDepth++
                        } else if (name == "li") {
                            val parentNode = if (currentOlDepth <= 1) rootNode else {
                                if (currentOlDepth - 1 < 32) activeNodesByDepth[currentOlDepth - 1] ?: rootNode else rootNode
                            }
                            val newNode = NavPointNode(parent = parentNode)
                            parentNode.children.add(newNode)
                            if (currentOlDepth < 32) {
                                activeNodesByDepth[currentOlDepth] = newNode
                            }
                        } else if (name == "a") {
                            inAnchorText = true
                            textBuilder.setLength(0)
                            currentHref = ""
                            for (i in 0 until parser.attributeCount) {
                                if (parser.getAttributeName(i).lowercase() == "href") {
                                    currentHref = parser.getAttributeValue(i) ?: ""
                                    break
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inAnchorText) {
                            textBuilder.append(parser.text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (name == "ol") {
                            currentOlDepth = java.lang.Math.max(0, currentOlDepth - 1)
                        } else if (name == "a") {
                            inAnchorText = false
                            if (currentOlDepth < 32) {
                                val currentNode = activeNodesByDepth[currentOlDepth]
                                if (currentNode != null) {
                                    currentNode.title = textBuilder.toString().trim()
                                    currentNode.href = currentHref
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }

            fun traverse(node: NavPointNode, depth: Int, parentTitle: String?) {
                if (node != rootNode) {
                    val title = node.title
                    val src = node.href
                    if (title.isNotEmpty()) {
                        val zipPath = getAbsoluteZipPath(src, navPath)
                        val fragment = getFragment(src)
                        val isSubch = depth > 0
                        items.add(TempTocItem(title, zipPath, fragment, src, isSubch, parentTitle))
                    }
                }
                for (child in node.children) {
                    traverse(child, depth + if (node == rootNode) 0 else 1, if (node == rootNode) null else node.title)
                }
            }

            traverse(rootNode, 0, null)

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing EPUB 3 Nav file", e)
        }

        if (items.isEmpty()) {
            try {
                val aTagRegex = Regex("<a\\b[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                val matches = aTagRegex.findAll(contentToParse)
                for (match in matches) {
                    val href = match.groups[1]?.value ?: continue
                    val rawTitle = match.groups[2]?.value ?: ""
                    val cleanTitle = rawTitle.replace("<[^>]*>".toRegex(), "").trim()
                    
                    val zipPath = getAbsoluteZipPath(href, navPath)
                    val fragment = getFragment(href)
                    items.add(TempTocItem(cleanTitle, zipPath, fragment, href))
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Error in loose EPUB3 nav parsing fallback", ex)
            }
        }

        return items
    }

    private fun parseEpub2Ncx(ncxXml: String, ncxPath: String): List<TempTocItem> {
        val items = mutableListOf<TempTocItem>()
        class NavPointNode(
            var id: String = "",
            var title: String = "",
            var href: String = "",
            val children: MutableList<NavPointNode> = mutableListOf(),
            var parent: NavPointNode? = null
        )

        val rootNode = NavPointNode(id = "root")
        var currentNode: NavPointNode = rootNode

        try {
            val parser = Xml.newPullParser()
            parser.setInput(ncxXml.reader())
            var eventType = parser.eventType
            
            var inLabelText = false
            val textBuilder = StringBuilder()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val rawName = parser.name
                val name = rawName?.lowercase()
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (name == "navpoint") {
                            var navId = ""
                            for (i in 0 until parser.attributeCount) {
                                if (parser.getAttributeName(i).lowercase() == "id") {
                                    navId = parser.getAttributeValue(i) ?: ""
                                    break
                                }
                            }
                            val newNode = NavPointNode(id = navId, parent = currentNode)
                            currentNode.children.add(newNode)
                            currentNode = newNode
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
                            currentNode.href = srcVal
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
                            currentNode.title = textBuilder.toString().trim()
                        } else if (name == "navpoint") {
                            currentNode = currentNode.parent ?: rootNode
                        }
                    }
                }
                eventType = parser.next()
            }
            
            fun traverse(node: NavPointNode, depth: Int, parentTitle: String?) {
                if (node != rootNode) {
                    val title = node.title
                    val src = node.href
                    if (title.isNotEmpty()) {
                        val zipPath = getAbsoluteZipPath(src, ncxPath)
                        val fragment = getFragment(src)
                        val isSubch = depth > 0
                        items.add(TempTocItem(title, zipPath, fragment, src, isSubch, parentTitle))
                    }
                }
                for (child in node.children) {
                    traverse(child, depth + if (node == rootNode) 0 else 1, if (node == rootNode) null else node.title)
                }
            }
            
            traverse(rootNode, 0, null)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing NCX", e)
        }
        return items
    }

    override fun parseTxtOrPdf(content: String, title: String): EpubStructureDomainModel {
        val chaptersList = mutableListOf<ParsedChapterDomain>()
        val sentencesList = mutableListOf<ParsedSentenceDomain>()

        val lines = content.split("\n")
        var currentChapterIndex = -1
        var currentChapterTitle = "Introduction"
        var currentSectionTitle = "Overview"
        var sentenceCount = 0

        fun saveCurrentChapter() {
            if (currentChapterIndex >= 0) {
                chaptersList.add(
                    ParsedChapterDomain(
                        title = currentChapterTitle,
                        isSubchapter = false,
                        parentTitle = null
                    )
                )
            }
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val isChapterLine = (trimmed.startsWith("# ") && trimmed.length < 150) || 
                                (trimmed.startsWith("Chapter", ignoreCase = true) && trimmed.contains(":") && trimmed.length < 100) || 
                                (trimmed.matches(Regex("^(CHAPTER|Chapter)\\s+\\d+.*")) && trimmed.length < 100)

            if (isChapterLine) {
                saveCurrentChapter()
                currentChapterIndex++
                currentChapterTitle = trimmed.removePrefix("# ").trim()
                currentSectionTitle = "Overview"
                sentenceCount = 0
                continue
            }

            val hasMarkdownHeaders = content.contains("#") || content.contains("##")
            val isSubheading = if (hasMarkdownHeaders) {
                (trimmed.startsWith("## ") || trimmed.startsWith("### ")) && trimmed.length < 150
            } else {
                trimmed.startsWith("## ") || trimmed.startsWith("### ") || (trimmed.length < 50 && !trimmed.endsWith(".") && !trimmed.endsWith("?"))
            }

            if (isSubheading) {
                currentSectionTitle = trimmed.removePrefix("## ").removePrefix("### ").trim()
                continue
            }

            val sentences = trimmed.split(Regex("(?<=[.!?])\\s+"))
            for (sent in sentences) {
                val sText = sent.trim()
                if (sText.isNotEmpty()) {
                    if (currentChapterIndex == -1) {
                        currentChapterIndex = 0
                        currentChapterTitle = "Introduction"
                    }
                    sentencesList.add(
                        ParsedSentenceDomain(
                            chapterIndex = currentChapterIndex,
                            sentenceIndex = sentenceCount,
                            text = sText,
                            sectionTitle = currentSectionTitle
                        )
                    )
                    sentenceCount++
                }
            }
        }
        
        saveCurrentChapter()

        if (chaptersList.isEmpty()) {
            chaptersList.add(
                ParsedChapterDomain(
                    title = "Main Text",
                    isSubchapter = false,
                    parentTitle = null
                )
            )
        }

        return EpubStructureDomainModel(
            title = title,
            author = "Academic Author",
            chapters = chaptersList,
            sentences = sentencesList
        )
    }

    private fun getAbsoluteZipPath(relativeHref: String, sourceFilePath: String): String {
        val folder = if (sourceFilePath.contains("/")) sourceFilePath.substringBeforeLast("/") + "/" else ""
        val combinedAndNormalized = (folder + relativeHref).lowercase()
            .replace('\\', '/').replace("//", "/").trimStart('/')
        return combinedAndNormalized.substringBefore("#")
    }

    private fun getFragment(href: String): String {
        return href.substringAfter("#", "")
    }

    private fun cleanForMatch(text: String): String {
        return text.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    private fun stripHtml(html: String, chapters: Set<String>, subheadings: Set<String>): String {
        val cleanTxt = html.replace("<style[^>]*>.*?</style>".toRegex(RegexOption.IGNORE_CASE), "")
            .replace("<script[^>]*>.*?</script>".toRegex(RegexOption.IGNORE_CASE), "")
            .replace("<[^>]*>".toRegex(), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .trim()
        
        val lines = cleanTxt.split("\n")
        val finalLines = mutableListOf<String>()
        for (l in lines) {
            val t = l.trim()
            if (t.isNotBlank()) {
                val matchKey = cleanForMatch(t)
                if (chapters.contains(matchKey)) {
                    finalLines.add("# $t")
                } else if (subheadings.contains(matchKey)) {
                    finalLines.add("## $t")
                } else {
                    finalLines.add(t)
                }
            }
        }
        return finalLines.joinToString("\n")
    }

    private fun emptyStructure() = EpubStructureDomainModel(null, null, emptyList(), emptyList())
}
