package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ReaderApplication
import com.example.data.gateway.AiGateway
import com.example.domain.model.*
import com.example.domain.repository.StudyRepository
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch


@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: StudyRepository = (application as ReaderApplication).repository
    private val prefs = application.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)

    // OpenRouter Key
    private val _openRouterKey = MutableStateFlow(prefs.getString("open_router_key", "") ?: "")
    val openRouterKey: StateFlow<String> = _openRouterKey.asStateFlow()

    // OpenRouter Model Selection
    private val _openRouterModel = MutableStateFlow(prefs.getString("open_router_model", "google/gemini-2.5-flash") ?: "google/gemini-2.5-flash")
    val openRouterModel: StateFlow<String> = _openRouterModel.asStateFlow()

    // OpenRouter Loaded Models list
    private val _openRouterModels = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val openRouterModels: StateFlow<List<Pair<String, String>>> = _openRouterModels.asStateFlow()

    // Active Color Theme Selector
    private val _activeTheme = MutableStateFlow(
        try {
            ColorThemeOption.valueOf(prefs.getString("active_theme", ColorThemeOption.COSMIC_SLATE.name) ?: ColorThemeOption.COSMIC_SLATE.name)
        } catch(e: Exception) {
            ColorThemeOption.COSMIC_SLATE
        }
    )
    val activeTheme: StateFlow<ColorThemeOption> = _activeTheme.asStateFlow()

    // Active downloadable google fontfamily tracking
    private val _activeFontName = MutableStateFlow(prefs.getString("active_font_name", "System") ?: "System")
    val activeFontName: StateFlow<String> = _activeFontName.asStateFlow()

    private val _activeFontFamily = MutableStateFlow<FontFamily?>(null)
    val activeFontFamily: StateFlow<FontFamily?> = _activeFontFamily.asStateFlow()

    // --- Active JIT Profile management ---
    private val _customProfiles = MutableStateFlow<List<MixProfile>>(emptyList())
    val customProfiles: StateFlow<List<MixProfile>> = _customProfiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow(prefs.getString("active_profile_id", "calm-focus") ?: "calm-focus")
    val activeProfileId: StateFlow<String> = _activeProfileId.asStateFlow()

    private val _activeProfile = MutableStateFlow<MixProfile>(
        MixProfile.BUILT_IN_PROFILES.first()
    )
    val activeProfile: StateFlow<MixProfile> = _activeProfile.asStateFlow()

    init {
        _customProfiles.value = loadCustomProfilesFromPrefs()
        _activeProfile.value = retrieveProfileById(_activeProfileId.value)
        updateLoadedFontFamily(_activeFontName.value)
        fetchAvailableOpenRouterModels()

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
            try {
                val fullText = originalSentences.joinToString(" ") { it.text }
                val key = _openRouterKey.value
                val model = _openRouterModel.value
                
                val rewritten = if (key.isNotBlank()) {
                    AiGateway.rewriteChapterOpenRouter(key, model, fullText, style)
                } else {
                    AiGateway.rewriteChapter(fullText, style)
                }
                
                repository.saveRewrite(bookId, chapterIndex, style, rewritten)
                loadRewrite(bookId, chapterIndex)
                _currentReadingMode.value = "REWRITE"
                _activeSentenceIndex.value = 0
            } catch (e: Exception) {
                e.printStackTrace()
                // Fail-safe fall back to standard Gemini or Simulated rewrite on network error
                android.widget.Toast.makeText(getApplication(), "OpenRouter failed: ${e.message}. Using offline default instead.", android.widget.Toast.LENGTH_LONG).show()
                val fullText = originalSentences.joinToString(" ") { it.text }
                val rewritten = AiGateway.rewriteChapter(fullText, style)
                repository.saveRewrite(bookId, chapterIndex, style, rewritten)
                loadRewrite(bookId, chapterIndex)
                _currentReadingMode.value = "REWRITE"
                _activeSentenceIndex.value = 0
            } finally {
                _isRewriting.value = false
            }
        }
    }

    // --- Dynamic Google Fonts & Custom Themes ---
    private fun updateLoadedFontFamily(name: String) {
        if (name == "System") {
            _activeFontFamily.value = null
        } else {
            val file = com.example.ui.util.FontDownloader.getFontFile(getApplication(), name)
            if (file.exists()) {
                try {
                    _activeFontFamily.value = FontFamily(androidx.compose.ui.text.font.Font(file))
                } catch (e: Exception) {
                    e.printStackTrace()
                    _activeFontFamily.value = null
                }
            } else {
                _activeFontFamily.value = null
            }
        }
    }

    fun downloadAndSetFont(fontName: String) {
        viewModelScope.launch {
            val success = com.example.ui.util.FontDownloader.downloadGoogleFont(getApplication(), fontName)
            if (success) {
                prefs.edit().putString("active_font_name", fontName).apply()
                _activeFontName.value = fontName
                updateLoadedFontFamily(fontName)
                android.widget.Toast.makeText(getApplication(), "Font $fontName downloaded successfully!", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(getApplication(), "Failed to download $fontName.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun setSystemFont() {
        prefs.edit().putString("active_font_name", "System").apply()
        _activeFontName.value = "System"
        _activeFontFamily.value = null
        android.widget.Toast.makeText(getApplication(), "System font active", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun saveOpenRouterSettings(key: String, model: String) {
        prefs.edit().putString("open_router_key", key).putString("open_router_model", model).apply()
        _openRouterKey.value = key
        _openRouterModel.value = model
        android.widget.Toast.makeText(getApplication(), "OpenRouter settings saved successfully!", android.widget.Toast.LENGTH_SHORT).show()
        fetchAvailableOpenRouterModels()
    }

    fun fetchAvailableOpenRouterModels() {
        viewModelScope.launch {
            try {
                val list = AiGateway.fetchOpenRouterModels()
                if (list.isNotEmpty()) {
                    _openRouterModels.value = list
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setTheme(theme: ColorThemeOption) {
        prefs.edit().putString("active_theme", theme.name).apply()
        _activeTheme.value = theme
    }

    private fun retrieveProfileById(id: String): MixProfile {
        val builtIn = MixProfile.BUILT_IN_PROFILES.firstOrNull { it.id == id }
        if (builtIn != null) return builtIn
        val customs = loadCustomProfilesFromPrefs()
        return customs.firstOrNull { it.id == id } ?: MixProfile.BUILT_IN_PROFILES.first()
    }

    fun selectMixProfile(profile: MixProfile) {
        prefs.edit().putString("active_profile_id", profile.id).apply()
        _activeProfileId.value = profile.id
        _activeProfile.value = profile
    }

    fun updateChaos(chaos: Float) {
        val current = _activeProfile.value
        val updated = current.copy(chaosLevel = chaos.coerceIn(0f, 1f))
        _activeProfile.value = updated
    }

    fun updateTempo(tempo: Float) {
        val current = _activeProfile.value
        val updated = current.copy(tempoScale = tempo.coerceIn(0.5f, 2.0f))
        _activeProfile.value = updated
    }

    fun updateSizeScale(size: Float) {
        val current = _activeProfile.value
        val updated = current.copy(sizeScale = size.coerceIn(0.7f, 1.5f))
        _activeProfile.value = updated
    }

    fun updateWeightContrast(weight: Float) {
        val current = _activeProfile.value
        val updated = current.copy(weightContrast = weight.coerceIn(0f, 1f))
        _activeProfile.value = updated
    }

    fun updateOpacityDepth(opacity: Float) {
        val current = _activeProfile.value
        val updated = current.copy(opacityDepth = opacity.coerceIn(0f, 1f))
        _activeProfile.value = updated
    }

    fun resetProfileToDefaults() {
        val current = _activeProfile.value
        val defaultOrOriginal = MixProfile.BUILT_IN_PROFILES.firstOrNull { it.id == current.id }
            ?: MixProfile.BUILT_IN_PROFILES.first()
        _activeProfile.value = defaultOrOriginal
    }

    fun saveAsCustomProfile(name: String) {
        val cleanName = name.trim()
        val id = "custom-${System.currentTimeMillis()}"
        val baseProfile = _activeProfile.value
        val newProfile = MixProfile(
            id = id,
            name = cleanName,
            isBuiltIn = false,
            chaosLevel = baseProfile.chaosLevel,
            tempoScale = baseProfile.tempoScale,
            sizeScale = baseProfile.sizeScale,
            weightContrast = baseProfile.weightContrast,
            opacityDepth = baseProfile.opacityDepth,
            alignmentBias = "mixed",
            allowRightAlign = true,
            animationSet = MixProfile.ACTIVE_CHOREOGRAPHIES,
            reduceMotion = false
        )
        saveCustomProfileToPrefs(newProfile)
        _customProfiles.value = loadCustomProfilesFromPrefs()
        selectMixProfile(newProfile)
        android.widget.Toast.makeText(getApplication(), "Saved custom profile '$cleanName'!", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun loadCustomProfilesFromPrefs(): List<MixProfile> {
        val list = mutableListOf<MixProfile>()
        val keys = prefs.all.keys
        for (key in keys) {
            if (key.startsWith("custom_profile:")) {
                val data = prefs.getString(key, null) ?: continue
                val parts = data.split(";")
                if (parts.size >= 7) {
                    try {
                        list.add(
                            MixProfile(
                                id = parts[0],
                                name = parts[1],
                                isBuiltIn = false,
                                chaosLevel = parts[2].toFloat(),
                                tempoScale = parts[3].toFloat(),
                                sizeScale = parts[4].toFloat(),
                                weightContrast = parts[5].toFloat(),
                                opacityDepth = parts[6].toFloat(),
                                alignmentBias = "mixed",
                                allowRightAlign = true,
                                animationSet = MixProfile.ACTIVE_CHOREOGRAPHIES,
                                reduceMotion = false
                            )
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        return list
    }

    private fun saveCustomProfileToPrefs(profile: MixProfile) {
        val data = "${profile.id};${profile.name};${profile.chaosLevel};${profile.tempoScale};${profile.sizeScale};${profile.weightContrast};${profile.opacityDepth}"
        prefs.edit().putString("custom_profile:${profile.id}", data).apply()
    }
}
