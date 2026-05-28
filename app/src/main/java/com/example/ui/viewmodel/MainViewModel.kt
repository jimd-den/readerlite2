package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ReaderApplication
import com.example.data.gateway.AiGateway
import com.example.domain.model.*
import com.example.domain.repository.StudyRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: StudyRepository = (application as ReaderApplication).repository

    init {
        viewModelScope.launch {
            repository.getAllClasses().collect { list ->
                if (list.isEmpty()) {
                    repository.createClass(
                        name = "General Academic Class",
                        description = "A default workspace to import your textbook chapters and study notes"
                    )
                }
            }
        }
    }

    // Reactive lists from DB
    val classes: StateFlow<List<StudyClass>> = repository.getAllClasses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedClassId = MutableStateFlow<String?>(null)
    val selectedClassId: StateFlow<String?> = _selectedClassId.asStateFlow()

    private val _selectedBookId = MutableStateFlow<String?>(null)
    val selectedBookId: StateFlow<String?> = _selectedBookId.asStateFlow()

    private val _selectedChapterIndex = MutableStateFlow<Int>(-1)
    val selectedChapterIndex: StateFlow<Int> = _selectedChapterIndex.asStateFlow()

    // Books for selected class
    val books: StateFlow<List<Book>> = _selectedClassId
        .flatMapLatest { classId ->
            if (classId != null) repository.getBooksForClass(classId)
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Chapters for selected book
    val chapters: StateFlow<List<Chapter>> = _selectedBookId
        .flatMapLatest { bookId ->
            if (bookId != null) repository.getChaptersForBook(bookId)
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Sentences for active chapter
    private val _sentences = MutableStateFlow<List<Sentence>>(emptyList())
    val sentences: StateFlow<List<Sentence>> = _sentences.asStateFlow()

    // Notes/Questions for selected book
    val notes: StateFlow<List<Note>> = _selectedBookId
        .flatMapLatest { bookId ->
            if (bookId != null) repository.getNotesForBook(bookId)
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI state holder
    private val _activeClass = MutableStateFlow<StudyClass?>(null)
    val activeClass: StateFlow<StudyClass?> = _activeClass.asStateFlow()

    private val _activeBook = MutableStateFlow<Book?>(null)
    val activeBook: StateFlow<Book?> = _activeBook.asStateFlow()

    private val _activeChapter = MutableStateFlow<Chapter?>(null)
    val activeChapter: StateFlow<Chapter?> = _activeChapter.asStateFlow()

    private val _activeSentenceIndex = MutableStateFlow<Int>(0)
    val activeSentenceIndex: StateFlow<Int> = _activeSentenceIndex.asStateFlow()

    private val _activeRewrite = MutableStateFlow<SavedRewrite?>(null)
    val activeRewrite: StateFlow<SavedRewrite?> = _activeRewrite.asStateFlow()

    private val _isRewriting = MutableStateFlow<Boolean>(false)
    val isRewriting: StateFlow<Boolean> = _isRewriting.asStateFlow()

    private val _currentReadingMode = MutableStateFlow<String>("ORIGINAL") // "ORIGINAL" or "REWRITE"
    val currentReadingMode: StateFlow<String> = _currentReadingMode.asStateFlow()

    // Chapter sentences adapted for rewritten text
    private val _rewrittenSentences = MutableStateFlow<List<String>>(emptyList())
    val rewrittenSentences: StateFlow<List<String>> = _rewrittenSentences.asStateFlow()

    fun selectClass(studyClass: StudyClass) {
        _activeClass.value = studyClass
        _selectedClassId.value = studyClass.id
        // Reset selections
        _activeBook.value = null
        _selectedBookId.value = null
        _activeChapter.value = null
        _selectedChapterIndex.value = -1
        _sentences.value = emptyList()
        _activeSentenceIndex.value = 0
    }

    fun createClass(name: String, description: String) {
        viewModelScope.launch {
            repository.createClass(name, description)
        }
    }

    fun deleteClass(classId: String) {
        viewModelScope.launch {
            repository.deleteClass(classId)
        }
    }

    fun selectBook(book: Book) {
        _activeBook.value = book
        _selectedBookId.value = book.id
        _activeChapter.value = null
        _selectedChapterIndex.value = -1
        _sentences.value = emptyList()
        _activeSentenceIndex.value = 0
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            repository.deleteBook(bookId)
            if (_selectedBookId.value == bookId) {
                _activeBook.value = null
                _selectedBookId.value = null
            }
        }
    }

    fun importBook(title: String, author: String, fileType: String, content: String) {
        val classId = _selectedClassId.value ?: return
        viewModelScope.launch {
            repository.importBook(
                classId = classId,
                title = title,
                author = author,
                fileType = fileType,
                filePath = "assets/$title",
                rawContent = content
            )
        }
    }

    fun selectChapter(chapter: Chapter) {
        _activeChapter.value = chapter
        _selectedChapterIndex.value = chapter.orderIndex
        _activeSentenceIndex.value = 0
        
        viewModelScope.launch {
            // Load original sentences
            repository.getSentencesForChapter(chapter.bookId, chapter.orderIndex)
                .collectLatest { sList ->
                    _sentences.value = sList
                }
        }

        // Check if there is an existing rewrite in DB
        loadRewrite(chapter.bookId, chapter.orderIndex)
    }

    private fun loadRewrite(bookId: String, chapterIndex: Int) {
        viewModelScope.launch {
            val rewrite = repository.getRewriteForChapter(bookId, chapterIndex)
            _activeRewrite.value = rewrite
            if (rewrite != null) {
                // Split rewritten text into sentence-sized blocks
                _rewrittenSentences.value = rewrite.rewrittenText
                    .split(Regex("(?<=[.!?])\\s+"))
                    .filter { it.trim().isNotEmpty() }
            } else {
                _rewrittenSentences.value = emptyList()
            }
        }
    }

    fun toggleReadingMode(mode: String) {
        _currentReadingMode.value = mode
        _activeSentenceIndex.value = 0
    }

    fun nextSentence() {
        val maxIndex = if (_currentReadingMode.value == "REWRITE") {
            _rewrittenSentences.value.size - 1
        } else {
            _sentences.value.size - 1
        }
        if (_activeSentenceIndex.value < maxIndex) {
            _activeSentenceIndex.value += 1
        }
    }

    fun previousSentence() {
        if (_activeSentenceIndex.value > 0) {
            _activeSentenceIndex.value -= 1
        }
    }

    fun setSentenceIndex(index: Int) {
        val maxIndex = if (_currentReadingMode.value == "REWRITE") {
            _rewrittenSentences.value.size - 1
        } else {
            _sentences.value.size - 1
        }
        if (index in 0..maxIndex) {
            _activeSentenceIndex.value = index
        }
    }

    fun addNote(content: String, type: NoteType) {
        val classId = _selectedClassId.value ?: return
        val bookId = _selectedBookId.value ?: return
        val chapterIdx = _selectedChapterIndex.value
        val sectionTitle = if (chapterIdx >= 0) {
            if (_currentReadingMode.value == "REWRITE") "AI Rewrite Section"
            else _sentences.value.getOrNull(_activeSentenceIndex.value)?.sectionTitle ?: "Outline Section"
        } else null

        val currentSentenceTxt = if (chapterIdx >= 0) {
            if (_currentReadingMode.value == "REWRITE") {
                _rewrittenSentences.value.getOrNull(_activeSentenceIndex.value)
            } else {
                _sentences.value.getOrNull(_activeSentenceIndex.value)?.text
            }
        } else null

        viewModelScope.launch {
            repository.addNote(
                classId = classId,
                bookId = bookId,
                chapterIndex = chapterIdx,
                sectionTitle = sectionTitle,
                sentenceIndex = if (chapterIdx >= 0) _activeSentenceIndex.value else null,
                content = content,
                type = type,
                snippet = currentSentenceTxt
            )
        }
    }

    fun addOutlineNote(chapterIndex: Int, sectionTitle: String?, content: String, type: NoteType) {
        val classId = _selectedClassId.value ?: return
        val bookId = _selectedBookId.value ?: return
        viewModelScope.launch {
            repository.addNote(
                classId = classId,
                bookId = bookId,
                chapterIndex = chapterIndex,
                sectionTitle = sectionTitle ?: "General Chapter Annotation",
                sentenceIndex = null,
                content = content,
                type = type,
                snippet = null
            )
        }
    }

    fun deleteNote(noteId: String) {
        viewModelScope.launch {
            repository.deleteNote(noteId)
        }
    }

    fun rewriteActiveChapter(style: String) {
        val bookId = _selectedBookId.value ?: return
        val chapterIndex = _selectedChapterIndex.value
        if (chapterIndex < 0) return

        val originalSentences = _sentences.value
        if (originalSentences.isEmpty()) return

        _isRewriting.value = true
        viewModelScope.launch {
            val fullText = originalSentences.joinToString(" ") { it.text }
            val rewritten = AiGateway.rewriteChapter(fullText, style)
            repository.saveRewrite(bookId, chapterIndex, style, rewritten)
            loadRewrite(bookId, chapterIndex)
            _currentReadingMode.value = "REWRITE"
            _activeSentenceIndex.value = 0
            _isRewriting.value = false
        }
    }
}
