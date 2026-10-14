package app.pantrypal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

private val PantryOrange = androidx.compose.ui.graphics.Color(0xFFF59E0B)
private val PantryInk = androidx.compose.ui.graphics.Color(0xFF1F2937)

data class Recipe(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val ingredients: String = "",
    val instructions: String = "",
    val sourceUrl: String = "",
    val prepTime: String = "",
    val cookTime: String = "",
    val servings: String = "2"
)

data class PlannedMeal(
    val id: String = UUID.randomUUID().toString(),
    val recipeId: String,
    val recipeTitle: String,
    val day: String,
    val mealType: String,
    val servings: Int
)

class RecipeStore(context: Context) {
    private val prefs = context.getSharedPreferences("pantrypal", Context.MODE_PRIVATE)

    fun load(): List<Recipe> = try {
        val array = JSONArray(prefs.getString("recipes", "[]") ?: "[]")
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Recipe(
                id = o.optString("id", UUID.randomUUID().toString()),
                title = o.optString("title", "Untitled recipe"),
                description = o.optString("description"),
                ingredients = o.optString("ingredients"),
                instructions = o.optString("instructions"),
                sourceUrl = o.optString("sourceUrl"),
                prepTime = o.optString("prepTime"),
                cookTime = o.optString("cookTime"),
                servings = o.optString("servings", "2")
            )
        }
    } catch (_: Exception) { emptyList() }

    fun backupJson(): String {
        val array = JSONArray()
        load().forEach { array.put(recipeToJson(it)) }
        return JSONObject().put("format", "PantryPal backup").put("version", 1).put("recipes", array).toString(2)
    }

    fun restoreJson(json: String): List<Recipe> {
        val root = JSONObject(json)
        require(root.optString("format") == "PantryPal backup") { "This file is not a PantryPal backup." }
        val array = root.optJSONArray("recipes") ?: throw IllegalArgumentException("The backup contains no recipe list.")
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::recipeFromJson) }
    }

    fun save(recipes: List<Recipe>) {
        val array = JSONArray()
        recipes.forEach { r -> array.put(recipeToJson(r)) }
        prefs.edit().putString("recipes", array.toString()).apply()
    }

    fun loadPlan(): List<PlannedMeal> = try {
        val array = JSONArray(prefs.getString("meal_plan", "[]") ?: "[]")
        (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { o ->
                PlannedMeal(o.optString("id", UUID.randomUUID().toString()), o.optString("recipeId"),
                    o.optString("recipeTitle"), o.optString("day", "Monday"),
                    o.optString("mealType", "Dinner"), o.optInt("servings", 2).coerceAtLeast(1))
            }
        }
    } catch (_: Exception) { emptyList() }

    fun savePlan(plan: List<PlannedMeal>) {
        val array = JSONArray()
        plan.forEach { p -> array.put(JSONObject().apply {
            put("id", p.id); put("recipeId", p.recipeId); put("recipeTitle", p.recipeTitle)
            put("day", p.day); put("mealType", p.mealType); put("servings", p.servings)
        }) }
        prefs.edit().putString("meal_plan", array.toString()).apply()
    }
}

private class RecipeImporter {
    suspend fun import(address: String): Recipe = withContext(Dispatchers.IO) {
        val parsed = URL(address.trim())
        require(parsed.protocol == "https" || parsed.protocol == "http") {
            "Enter a valid http or https URL."
        }
        val connection = (parsed.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 12000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PantryPal/0.1 open-source recipe importer")
        }
        try {
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val scripts = Regex("(?is)<script[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>")
            for (match in scripts.findAll(html)) {
                val json = match.groupValues[1].trim()
                val candidates = try {
                    val obj = JSONObject(json)
                    listOf(obj)
                } catch (_: Exception) {
                    try {
                        val arr = JSONArray(json)
                        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    } catch (_: Exception) { emptyList() }
                }
                candidates.forEach { candidate ->
                    findRecipe(candidate)?.let { return@withContext toRecipe(it, address) }
                }
            }
            throw IllegalStateException("No structured recipe found. Try another website or enter the recipe manually.")
        } finally {
            connection.disconnect()
        }
    }

    private fun findRecipe(o: JSONObject): JSONObject? {
        val type = o.opt("@type")?.toString().orEmpty()
        if (type.contains("Recipe", ignoreCase = true)) return o
        val graph = o.optJSONArray("@graph")
        if (graph != null) for (i in 0 until graph.length()) {
            graph.optJSONObject(i)?.let { findRecipe(it)?.let { found -> return found } }
        }
        val itemList = o.optJSONArray("itemListElement")
        if (itemList != null) for (i in 0 until itemList.length()) {
            val item = itemList.optJSONObject(i) ?: continue
            val nested = item.optJSONObject("item") ?: item
            findRecipe(nested)?.let { return it }
        }
        return null
    }

    private fun toRecipe(o: JSONObject, address: String): Recipe {
        val ingredients = o.optJSONArray("recipeIngredient")?.let { array ->
            (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }.joinToString("\n")
        } ?: ""
        val steps = mutableListOf<String>()
        fun readSteps(value: Any?) {
            when (value) {
                is String -> if (value.isNotBlank()) steps.add(value)
                is JSONObject -> {
                    val text = value.optString("text")
                    if (text.isNotBlank()) steps.add(text)
                    else value.optJSONArray("itemListElement")?.let { a ->
                        for (i in 0 until a.length()) readSteps(a.opt(i))
                    }
                }
                is JSONArray -> for (i in 0 until value.length()) readSteps(value.opt(i))
            }
        }
        readSteps(o.opt("recipeInstructions"))
        return Recipe(
            title = o.optString("name").ifBlank { "Imported recipe" },
            description = o.optString("description"),
            ingredients = ingredients,
            instructions = steps.joinToString("\n\n"),
            sourceUrl = address,
            prepTime = o.optString("prepTime"),
            cookTime = o.optString("cookTime"),
            servings = o.opt("recipeYield")?.toString()?.replace("[", "")?.replace("]", "")?.replace("\"", "") ?: "2"
        )
    }
}

private data class BrowseSite(val name: String, val domain: String, val emoji: String)

private val browseSites = listOf(
    BrowseSite("BBC Good Food", "bbcgoodfood.com", "🍲"),
    BrowseSite("Recipe Rebel", "thereciperebel.com", "🥘"),
    BrowseSite("Allrecipes", "allrecipes.com", "🍝"),
    BrowseSite("Jamie Oliver", "jamieoliver.com", "🍋"),
    BrowseSite("RecipeTin Eats", "recipetineats.com", "🍜"),
    BrowseSite("Simply Recipes", "simplyrecipes.com", "🥗")
)

private data class RecipeSearchResult(val title: String, val url: String)

private class RecipeWebSearch {
    suspend fun search(query: String, siteDomain: String? = null): List<RecipeSearchResult> = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "Enter a dish or ingredient to search for." }
        val sitePrefix = siteDomain?.takeIf { it.isNotBlank() }?.let { "site:$it " }.orEmpty()
        val encoded = URLEncoder.encode("${sitePrefix}${query.trim()} recipe", "UTF-8")
        val endpoints = listOf(
            "https://html.duckduckgo.com/html/?q=$encoded",
            "https://lite.duckduckgo.com/lite/?q=$encoded",
            "https://www.google.com/search?q=$encoded"
        )
        var lastError: Exception? = null
        for (endpoint in endpoints) {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 12000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 9 Pro XL) AppleWebKit/537.36 Chrome/129.0.0.0 Mobile Safari/537.36")
                setRequestProperty("Accept-Language", "en-GB,en;q=0.9")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            }
            try {
                val status = connection.responseCode
                if (status !in 200..299) throw IllegalStateException("Search provider returned HTTP $status.")
                val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val anchors = Regex("""(?is)<a\\b([^>]*)>(.*?)</a>""").findAll(html)
                val results = anchors.mapNotNull { match ->
                    val attributes = match.groupValues[1]
                    val classes = Regex("""(?is)\\bclass\\s*=\\s*["']([^"']*)["']""")
                        .find(attributes)?.groupValues?.get(1).orEmpty()
                    val rawHref = Regex("""(?is)\\bhref\\s*=\\s*["']([^"']+)["']""")
                        .find(attributes)?.groupValues?.get(1) ?: return@mapNotNull null
                    var link = decodeHtml(rawHref.trim())
                    if (link.startsWith("//")) link = "https:$link"
                    try {
                        val parsedLink = URL(link)
                        val host = parsedLink.host.lowercase().removePrefix("www.")
                        if (host.endsWith("duckduckgo.com") && parsedLink.path == "/l/") {
                            val target = parsedLink.query?.split("&")?.firstOrNull { it.startsWith("uddg=") }
                                ?.substringAfter("uddg=")
                            if (target != null) link = URLDecoder.decode(target, "UTF-8")
                        } else if (host == "google.com" || host == "www.google.com") {
                            val target = parsedLink.query?.split("&")?.firstOrNull { it.startsWith("q=") || it.startsWith("url=") }
                                ?.substringAfter("=")
                            if (parsedLink.path == "/url" && target != null) link = URLDecoder.decode(target, "UTF-8")
                        }
                    } catch (_: Exception) { return@mapNotNull null }
                    val title = decodeHtml(match.groupValues[2].replace(Regex("""(?is)<[^>]+>"""), ""))
                        .replace(Regex("""\\s+"""), " ").trim()
                    val validWebUrl = link.startsWith("https://") || link.startsWith("http://")
                    val host = try { URL(link).host.lowercase().removePrefix("www.") } catch (_: Exception) { "" }
                    val isSearchProvider = host == "google.com" || host.endsWith(".google.com") ||
                        host == "duckduckgo.com" || host.endsWith(".duckduckgo.com")
                    val matchesSite = siteDomain.isNullOrBlank() || host == siteDomain || host.endsWith(".$siteDomain")
                    val hasResultClass = classes.contains("result__a") || classes.contains("result-link") ||
                        classes.contains("MjjYud") || classes.contains("LC20lb")
                    val plausibleResult = hasResultClass || (host.isNotBlank() && !isSearchProvider &&
                        (siteDomain != null || title.contains("recipe", true) ||
                            link.substringAfter(host).contains("/") && title.length > 8))
                    if (!validWebUrl || isSearchProvider || !matchesSite || !plausibleResult || title.isBlank()) {
                        return@mapNotNull null
                    }
                    RecipeSearchResult(title, link)
                }.distinctBy { it.url }.take(12).toList()
                if (results.isNotEmpty()) return@withContext results
                lastError = IllegalStateException("The search page loaded, but its results could not be read.")
            } catch (e: Exception) {
                lastError = e
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException(
            lastError?.message ?: "Online search failed. Try again or import a recipe URL directly.",
            lastError
        )
