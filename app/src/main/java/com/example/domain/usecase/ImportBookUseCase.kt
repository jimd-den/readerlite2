package com.example.domain.usecase

import com.example.domain.repository.EpubExtractor
import com.example.domain.repository.StudyRepository
import com.example.domain.model.EpubStructureDomainModel
import java.io.InputStream

class ImportBookUseCase(
    private val studyRepository: StudyRepository,
    private val epubExtractor: EpubExtractor
) {
    suspend fun execute(
        classId: String,
        title: String,
        author: String,
        fileType: String,
        filePath: String,
        rawContent: String,
        inputStreamProvider: () -> InputStream?
    ): String {
        val structure = if (fileType == "EPUB") {
            val stream = inputStreamProvider()
            if (stream != null) {
                try {
                    epubExtractor.parseEpub(stream)
                } catch (e: Exception) {
                    e.printStackTrace()
                    epubExtractor.parseTxtOrPdf(rawContent, title)
                }
            } else {
                epubExtractor.parseTxtOrPdf(rawContent, title)
            }
        } else {
            epubExtractor.parseTxtOrPdf(rawContent, title)
        }

        val finalTitle = if (!structure.title.isNullOrEmpty()) structure.title else title
        val finalAuthor = if (!structure.author.isNullOrEmpty()) structure.author else author

        return studyRepository.importBook(
            classId = classId,
            title = finalTitle,
            author = finalAuthor,
            fileType = fileType,
            filePath = filePath,
            structure = structure
        )
    }
}
