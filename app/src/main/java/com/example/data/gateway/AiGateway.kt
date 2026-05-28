package com.example.data.gateway

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AiGateway {
    private const val TAG = "AiGateway"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun rewriteChapter(originalText: String, rewriteStyle: String): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY" || apiKey.contains("placeholder", ignoreCase = true)) {
            Log.w(TAG, "No valid Gemini API key found. Using beautiful local simulation.")
            return@withContext simulateRewrite(originalText, rewriteStyle)
        }

        val prompt = """
            You are a master educator design genius like Shigeru Miyamoto. 
            Rewrite the following educational text into the specified style: "$rewriteStyle".
            Requirements:
            1. Keep it highly engaging and clear.
            2. Divide with clear structural subheadings using "## Subheading Name" or "Chapter X: Title".
            3. Ensure each sentence is impactful, because the user reads this one sentence at a time.
            4. Retain all major concepts but make them effortless to read.
            
            Original Text:
            $originalText
        """.trimIndent()

        try {
            // Build Gemini request body
            val requestJson = JSONObject().apply {
                put("contents", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", org.json.JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
            }

            val requestBody = requestJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "API call failed with code: ${response.code}. Falling back to simulation.")
                    return@withContext simulateRewrite(originalText, rewriteStyle)
                }

                val responseBodyStr = response.body?.string() ?: ""
                val responseJson = JSONObject(responseBodyStr)
                val text = responseJson
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                
                return@withContext text
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating AI rewrite", e)
            return@withContext simulateRewrite(originalText, rewriteStyle)
        }
    }

    private fun simulateRewrite(originalText: String, style: String): String {
        return when (style) {
            "Elementary / Ultra-Simple" -> """
                # Chapter Rewrite: Effortless Clarity
                ## Starting with a Clear Mind
                Let's make this simple: Learning is like playing a video game.
                Before you run into a level, you look at the total map to see what is ahead.
                This is exactly what "Surveying" means.
                If you look at the outline first, you will not get lost.
                
                ## Asking Great Questions
                Next, you turn headings into little puzzles or questions.
                For example, instead of reading "Continuous Memory Allocation", you ask, "How does memory stay in a neat row?"
                This keeps your curiosity active, just like scouting for secret treasures.
                Every paragraph becomes an answer you are actively hunting for!
            """.trimIndent()
            "Socratic / Inquiry-Driven" -> """
                # Chapter Rewrite: The Socratic Journey
                ## Why do we Survey First?
                Have you ever wondered why we read chapters from start to finish without pausing?
                Does an explorer enter a dungeon without looking at the geographic blueprints?
                By surveying the headings first, we build the scaffolding of the mind.
                What happens to a building constructed without a blueprint? It collapses under the weight of details.
                
                ## The Power of the Question
                What is a Heading if not an unasked question waiting for a light?
                By transforming headlines into direct, searching questions, we awaken our natural intelligence.
                How can we record meaning unless we are actively seeking an answer to our own query?
                The note you write is the prize of a question answered.
            """.trimIndent()
            else -> """
                # Chapter Rewrite: High Hooking Action
                ## The Hook of Structure
                Look at this layout: Clean headings are the checkpoints of your study speedrun.
                Survey the map first. Identify where the chapters begin and where the subheadings divide.
                This is your tactical setup. Don't skip it, or you're running blind.
                
                ## Active Capture Loop
                Now convert those subheadings into active questions.
                Read sentence by sentence with explosive typography variations to keep your focus locked.
                Record notes on the fly and review them regularly to lock those facts into your long-term directory.
            """.trimIndent()
        }
    }
}
