package com.example.algosolver

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@SpringBootApplication
class AlgoSolverApplication

fun main(args: Array<String>) {
    runApplication<AlgoSolverApplication>(*args)
}

// --- CORS Configuration ---
@Configuration
class CorsConfig : WebMvcConfigurer {
    /**
     * Configures global CORS rules.
     * This handles the preflight OPTIONS request automatically by returning 200 OK
     * with appropriate headers, and includes CORS headers on the final response.
     */
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOrigins("*") // Allow all origins (use specific URL in production)
            .allowedMethods("GET", "POST", "OPTIONS") // Required methods
            .allowedHeaders("*") // Allow all headers
    }
}

// --- DTOs ---
data class SolveRequest(val platform: String, val slug: String, val language: String)
data class SolveResponse(val code: String? = null, val error: String? = null)
data class ProblemData(val content: String, val sampleTestCase: String)

@RestController
class SolverController(@Value("\${GEMINI_API_KEY:}") val geminiApiKey: String) {

    private val objectMapper = jacksonObjectMapper()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    companion object {
        const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-09-2025:generateContent"
        const val LEETCODE_GRAPHQL_URL = "https://leetcode.com/graphql"
    }

    @PostMapping("/api/solve")
    fun solveProblem(@RequestBody request: SolveRequest): SolveResponse {
        // 1. Validation
        if (!request.platform.equals("leetcode", ignoreCase = true)) {
            return SolveResponse(error = "Only 'leetcode' platform is currently supported.")
        }
        if (request.slug.isBlank()) {
            return SolveResponse(error = "Problem slug is required.")
        }

        return try {
            // 2. Fetch Problem Data from LeetCode
            val problemData = fetchLeetCodeData(request.slug)
                ?: return SolveResponse(error = "Could not find problem with slug: ${request.slug}")

            // 3. Generate Solution using Gemini
            val solutionCode = generateGeminiSolution(problemData, request.language)
            SolveResponse(code = solutionCode)

        } catch (e: Exception) {
            e.printStackTrace()
            SolveResponse(error = "Internal Server Error: ${e.message}")
        }
    }

    private fun fetchLeetCodeData(slug: String): ProblemData? {
        val query = """
            query questionData(${"$"}titleSlug: String!) {
              question(titleSlug: ${"$"}titleSlug) {
                content
                sampleTestCase
              }
            }
        """.trimIndent()

        val requestBody = mapOf(
            "query" to query,
            "variables" to mapOf("titleSlug" to slug)
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create(LEETCODE_GRAPHQL_URL))
            .header("Content-Type", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw IOException("LeetCode API failed with status: ${response.statusCode()}")
        }

        val root = objectMapper.readTree(response.body())
        val questionNode = root.path("data").path("question")

        if (questionNode.isMissingNode || questionNode.isNull) {
            return null
        }

        return ProblemData(
            content = questionNode.path("content").asText(),
            sampleTestCase = questionNode.path("sampleTestCase").asText()
        )
    }

    private fun generateGeminiSolution(data: ProblemData, language: String): String {
        if (geminiApiKey.isBlank()) {
            throw IOException("GEMINI_API_KEY environment variable is not set.")
        }

        val promptText = """
            You are an expert software engineer.
            Task: Write a $language solution for the following LeetCode problem.
            Problem Description (HTML): ${data.content}
            Sample Test Case: ${data.sampleTestCase}

            Requirements:
            1. Return ONLY the raw code.
            2. Do not wrap in markdown blocks (no ```).
            3. Do not include explanations, just the solution class/function.
            4. Ensure it handles the sample test case.
        """.trimIndent()

        // Build Gemini JSON Payload
        val payload = mapOf(
            "contents" to listOf(
                mapOf("parts" to listOf(mapOf("text" to promptText)))
            )
        )

        val endpoint = "$GEMINI_URL?key=$geminiApiKey"

        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw IOException("Gemini API failed with status: ${response.statusCode()} Body: ${response.body()}")
        }

        val root = objectMapper.readTree(response.body())
        val rawText = root.path("candidates")
            .get(0)
            .path("content")
            .path("parts")
            .get(0)
            .path("text")
            .asText()

        return cleanResponse(rawText)
    }

    private fun cleanResponse(rawText: String): String {
        var text = rawText.trim()
        if (text.startsWith("```")) {
            val firstNewline = text.indexOf("\n")
            if (firstNewline != -1) {
                text = text.substring(firstNewline + 1)
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length - 3)
            }
        }
        return text.trim()
    }
}