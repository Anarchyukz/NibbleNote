package app.pantrypal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
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
    val servings: String = "2",
    val calories: String = "",
    val protein: String = "",
    val carbs: String = "",
    val fat: String = ""
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
                servings = o.optString("servings", "2"),
                calories = o.optString("calories"), protein = o.optString("protein"),
                carbs = o.optString("carbs"), fat = o.optString("fat")
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
        val nutrition = o.optJSONObject("nutrition")
        fun nutritionValue(key: String): String {
            val raw = nutrition?.optString(key)?.takeIf { it.isNotBlank() && it != "null" } ?: return ""
            val number = Regex("""-?\d+(?:[.,]\d+)?""").find(raw)?.value?.replace(',', '.') ?: return ""
            val parsed = number.toDoubleOrNull() ?: return ""
            return if (parsed % 1.0 == 0.0) parsed.toInt().toString() else String.format(java.util.Locale.UK, "%.1f", parsed)
        }
        return Recipe(
            title = o.optString("name").ifBlank { "Imported recipe" },
            description = o.optString("description"),
            ingredients = ingredients,
            instructions = steps.joinToString("\n\n"),
            sourceUrl = address,
            prepTime = o.optString("prepTime"),
            cookTime = o.optString("cookTime"),
            servings = o.opt("recipeYield")?.toString()?.replace("[", "")?.replace("]", "")?.replace("\"", "") ?: "2",
            calories = nutritionValue("calories"), protein = nutritionValue("proteinContent"),
            carbs = nutritionValue("carbohydrateContent"), fat = nutritionValue("fatContent")
        )
    }
}

private data class BrowseSite(val name: String, val domain: String, val emoji: String, val category: String = "Everyday", val path: String = "")
private data class BrowseCategory(val title: String, val subtitle: String, val emoji: String, val query: String)

private val browseCategories = listOf(
    BrowseCategory("Weight loss & lighter meals", "Lighter recipes and balanced meal ideas.", "🥗", "low calorie healthy recipes"),
    BrowseCategory("Comfort food", "Cosy favourites, pies, pasta bakes, curries and puddings.", "🍲", "comfort food recipes"),
    BrowseCategory("Winter warmers", "Soups, stews, slow-cooker dinners and hearty seasonal dishes.", "❄️", "winter warmer soup stew slow cooker recipes"),
    BrowseCategory("Summer & fresh", "Salads, barbecue, fresh lunches and fruity desserts.", "☀️", "summer fresh salad barbecue recipes"),
    BrowseCategory("High protein", "Protein-rich meals, meal prep and filling lunches.", "💪", "high protein chicken fish vegetarian recipes"),
    BrowseCategory("Budget-friendly", "Cheap ingredients and family-friendly meals.", "💷", "cheap budget family dinner recipes"),
    BrowseCategory("Quick & easy", "Weeknight dinners and recipes with few ingredients.", "⏱️", "quick easy 20 minute recipes"),
    BrowseCategory("Vegetarian & plant-based", "Meat-free meals and colourful vegetable dishes.", "🌱", "vegetarian vegan plant based recipes"),
    BrowseCategory("Baking & sweet treats", "Cakes, biscuits, bread and desserts.", "🍰", "easy baking cake biscuit dessert recipes"),
    BrowseCategory("World flavours", "Curries, noodles and dishes from around the world.", "🌍", "easy world cuisine curry noodle recipes")
)

private val browseSites = listOf(
    BrowseSite("BBC Good Food", "www.bbcgoodfood.com", "🍲", "Comfort food", "/recipes"),
    BrowseSite("Recipe Rebel", "thereciperebel.com", "🥘", "Quick & easy"),
    BrowseSite("Allrecipes", "allrecipes.com", "🍝", "Comfort food"),
    BrowseSite("Jamie Oliver", "jamieoliver.com", "🍋", "Summer & fresh"),
    BrowseSite("RecipeTin Eats", "recipetineats.com", "🍜", "World flavours"),
    BrowseSite("Simply Recipes", "simplyrecipes.com", "🥗", "Quick & easy"),
    BrowseSite("EatingWell", "eatingwell.com", "🥦", "Weight loss & lighter meals"),
    BrowseSite("Skinnytaste", "skinnytaste.com", "🥗", "Weight loss & lighter meals"),
    BrowseSite("Healthy Food Guide", "healthyfood.com", "🥑", "Weight loss & lighter meals"),
    BrowseSite("NHS recipes", "nhs.uk", "💚", "Weight loss & lighter meals", "/healthier-families/recipes/"),
    BrowseSite("The Kitchn", "thekitchn.com", "🥘", "Comfort food"),
    BrowseSite("Delish", "delish.com", "🧀", "Comfort food"),
    BrowseSite("Good Housekeeping", "www.goodhousekeeping.com", "🍲", "Winter warmers", "/food-recipes/"),
    BrowseSite("Olive Magazine", "olivemagazine.com", "🥘", "Winter warmers"),
    BrowseSite("The Spruce Eats", "thespruceeats.com", "🍛", "Winter warmers"),
    BrowseSite("Taste of Home", "tasteofhome.com", "🥧", "Winter warmers"),
    BrowseSite("Mob", "mob.co.uk", "🥙", "Summer & fresh"),
    BrowseSite("Love and Lemons", "loveandlemons.com", "🍋", "Summer & fresh"),
    BrowseSite("Smitten Kitchen", "smittenkitchen.com", "🍅", "Summer & fresh"),
    BrowseSite("The Protein Chef", "theproteinchef.co", "🥩", "High protein"),
    BrowseSite("Fit Men Cook", "fitmencook.com", "🍗", "High protein"),
    BrowseSite("Muscle & Strength", "muscleandstrength.com", "💪", "High protein"),
    BrowseSite("Cooking on a Bootstrap", "cookingonabootstrap.com", "🥫", "Budget-friendly"),
    BrowseSite("Budget Bytes", "budgetbytes.com", "🫘", "Budget-friendly"),
    BrowseSite("Tesco Real Food", "realfood.tesco.com", "🛒", "Budget-friendly"),
    BrowseSite("Food Network", "foodnetwork.com", "🍳", "Quick & easy"),
    BrowseSite("Tasty", "tasty.co", "🍴", "Quick & easy"),
    BrowseSite("Minimalist Baker", "minimalistbaker.com", "🌱", "Vegetarian & plant-based"),
    BrowseSite("Cookie and Kate", "cookieandkate.com", "🥑", "Vegetarian & plant-based"),
    BrowseSite("Forks Over Knives", "forksoverknives.com", "🥕", "Vegetarian & plant-based"),
    BrowseSite("King Arthur Baking", "kingarthurbaking.com", "🍞", "Baking & sweet treats"),
    BrowseSite("Jane's Patisserie", "janespatisserie.com", "🧁", "Baking & sweet treats"),
    BrowseSite("Sally's Baking Addiction", "sallysbakingaddiction.com", "🍪", "Baking & sweet treats"),
    BrowseSite("Maangchi", "maangchi.com", "🍚", "World flavours"),
    BrowseSite("Just One Cookbook", "justonecookbook.com", "🍱", "World flavours"),
    BrowseSite("The Woks of Life", "thewoksoflife.com", "🥢", "World flavours"),
    BrowseSite("Serious Eats", "seriouseats.com", "🌮", "World flavours")
)

private data class RecipeSearchResult(val title: String, val url: String)

private val randomBrowseQueries = listOf(
    "easy dinner", "chicken recipes", "pasta recipes", "fish recipes",
    "vegetarian recipes", "quick lunch", "dessert recipes", "breakfast recipes",
    "soup recipes", "curry recipes", "traybake recipes", "healthy recipes",
    "slow cooker recipes", "rice recipes", "baking recipes", "salad recipes"
)

private class RecipeWebSearch {
    suspend fun search(query: String, siteDomain: String? = null): List<RecipeSearchResult> = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "Enter a dish or ingredient to search for." }
        val sitePrefix = siteDomain?.takeIf { it.isNotBlank() }?.let { "site:$it " }.orEmpty()
        val encoded = URLEncoder.encode("${sitePrefix}${query.trim()} recipe", "UTF-8")
        val endpoints = listOf(
            "https://www.bing.com/search?q=$encoded&setlang=en-GB",
            "https://html.duckduckgo.com/html/?q=$encoded",
            "https://lite.duckduckgo.com/lite/?q=$encoded"
        )
        var lastError: Exception? = null
        for (endpoint in endpoints) {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 12000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
                setRequestProperty("Accept-Language", "en-GB,en;q=0.9")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
            try {
                val status = connection.responseCode
                if (status !in 200..299) throw IllegalStateException("Search provider returned HTTP $status.")
                val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val isBing = endpoint.contains("bing.com")
                val anchors = Regex("""(?is)<a\b([^>]*)>(.*?)</a>""").findAll(html)
                val parsed = anchors.mapNotNull { match ->
                    val attributes = match.groupValues[1]
                    val classes = Regex("""(?is)\bclass\s*=\s*["']([^"']*)["']""")
                        .find(attributes)?.groupValues?.get(1).orEmpty()
                    val rawHref = Regex("""(?is)\bhref\s*=\s*["']([^"']+)["']""")
                        .find(attributes)?.groupValues?.get(1) ?: return@mapNotNull null
                    var link = decodeHtml(rawHref.trim())
                    if (link.startsWith("//")) link = "https:$link"
                    if (link.contains("duckduckgo.com/l/?")) {
                        val target = Regex("[?&]uddg=([^&]+)").find(link)?.groupValues?.get(1)
                        if (target != null) link = URLDecoder.decode(target, "UTF-8")
                    }
                    if (isBing && link.contains("bing.com/ck/a")) {
                        val target = Regex("[?&]u=([^&]+)").find(link)?.groupValues?.get(1)
                        if (target != null) {
                            val encodedTarget = URLDecoder.decode(target, "UTF-8")
                            val payload = if (encodedTarget.startsWith("a1")) encodedTarget.substring(2) else encodedTarget
                            val decodedTarget = try {
                                String(java.util.Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
                            } catch (_: Exception) { "" }
                            if (decodedTarget.startsWith("http://") || decodedTarget.startsWith("https://")) link = decodedTarget
                        }
                    }
                    val title = decodeHtml(match.groupValues[2].replace(Regex("""(?is)<[^>]+>"""), ""))
                        .replace(Regex("""\s+"""), " ").trim()
                    val resultHost = try { URL(link).host.lowercase().removePrefix("www.") } catch (_: Exception) { "" }
                    val resultClass = classes.contains("result__a") || classes.contains("result-link") ||
                        classes.contains("b_algo") || classes.contains("b_title")
                    if ((!link.startsWith("https://") && !link.startsWith("http://")) ||
                        title.length < 3 ||
                        title.lowercase() in listOf("images", "videos", "maps", "news", "shopping", "sign in", "more")) {
                        return@mapNotNull null
                    }
                    val inBingResultBlock = isBing && html.substring(0, match.range.first)
                        .takeLast(600).contains("b_algo", ignoreCase = true)
                    Triple(RecipeSearchResult(title, link), if (resultClass || inBingResultBlock) 0 else 1, match.range.first)
                }.distinctBy { it.first.url }
                    .sortedWith(compareBy<Triple<RecipeSearchResult, Int, Int>> { it.second }.thenBy { it.third })
                    .map { it.first }.take(12).toList()
                if (parsed.isNotEmpty()) {
                    // Prefer known cooking sites and likely recipe URLs, but don't require structured data:
                    // many legitimate recipe pages omit JSON-LD or block direct validation requests.
                    val recipeSites = listOf(
                        "bbcgoodfood.com", "goodhousekeeping.com", "allrecipes.com", "simplyrecipes.com",
                        "recipetineats.com", "delish.com", "taste.com.au", "thekitchn.com", "food.com",
                        "foodnetwork.com", "jamieoliver.com", "bbc.co.uk/food", "mob.co.uk",
                        "budgetbytes.com", "loveandlemons.com", "cookieandkate.com", "seriouseats.com",
                        "sallysbakingaddiction.com", "waitrose.com", "tesco.com"
                    )
                    fun recipeScore(result: RecipeSearchResult): Int {
                        val url = result.url.lowercase()
                        val title = result.title.lowercase()
                        val host = try { URL(url).host.removePrefix("www.") } catch (_: Exception) { "" }
                        val blockedHosts = listOf("youtube.com", "youtu.be", "facebook.com", "instagram.com",
                            "pinterest.com", "tiktok.com", "reddit.com")
                        if (blockedHosts.any { host == it || host.endsWith(".$it") }) return -100
                        val articleSignals = listOf(
                            "news", "newsletter", "podcast", "restaurant review", "product review",
                            "best restaurants", "meal kit", "kitchen equipment", "cookware review",
                            "food industry", "food trends", "what is", "history of", "explained",
                            "calorie calculator", "nutrition calculator", "weekly menu"
                        )
                        if (articleSignals.any { it in title }) return -100
                        // Search engines sometimes return grocery products for ingredient queries
                        // (for example, tins of branded broth) even when we append "recipe".
                        val productTitleSignals = listOf(
                            "buy ", "price ", "tin of ", "tins of ", "can of ", "cans of ",
                            "pack of ", "multipack", "multi pack", "grocery product",
                            "product details", "add to basket", "add to cart", "heinz broth"
                        )
                        val productPathSignals = listOf(
                            "/products/", "/product/", "/p/", "/shop/product",
                            "/groceries/products/", "/catalogue/item/"
                        )
                        if (productTitleSignals.any { it in title } ||
                            productPathSignals.any { it in url }) return -100
                        // Match a known recipe publisher against the actual hostname only.
                        // A supermarket's product URL must not score as a recipe just because
                        // its domain is listed for its recipe section.
                        val knownSite = recipeSites.any { host == it || host.endsWith(".$it") }
                        val recipePath = listOf("/recipe", "/recipes/", "/food/recipes", "/dish/", "/how-to-make/")
                            .any { it in url }
                        val recipeTitle = listOf("recipe", "how to make", "ingredients", "curry", "soup",
                            "pasta", "chicken", "cake", "bread", "salad", "stew", "cookies", "brownies",
                            "roast", "casserole", "bake", "noodles", "pizza", "rice", "pudding")
                            .any { it in title }
                        val queryWords = query.lowercase().split(Regex("\\s+"))
                            .filter { it.length > 2 && it !in setOf("the", "and", "with", "recipe", "recipes") }
                        val queryMatches = queryWords.count { it in title }
                        val recipeIntent = recipePath || "recipe" in title || "recipes" in title ||
                            "how to make" in title || "ingredients" in title
                        // Generic ingredient matches alone are not enough: keep pages that have
                        // a recipe signal, or strong evidence from a known recipe publisher.
                        if (!recipeIntent && !knownSite && queryMatches < 2) return -100
                        return (if (knownSite) 3 else 0) +
                            (if (recipePath) 4 else 0) +
                            (if ("recipe" in title || "recipes" in title) 4 else 0) +
                            (if ("how to make" in title || "ingredients" in title) 2 else 0) +
                            (if (recipeTitle) 2 else 0) + queryMatches
                    }
                    // Rank recipe pages ahead of generic articles, while retaining unverified
                    // results from unfamiliar sites when the title and URL look relevant.
                    val candidates = parsed.map { it to recipeScore(it) }
                        .filter { it.second > -100 }
                        .sortedByDescending { it.second }
                        .map { it.first }
                        .distinctBy { it.url }
                        .take(12)
                    val checked = coroutineScope {
                        candidates.take(8).mapIndexed { index, result ->
                            async(Dispatchers.IO) {
                                if (hasRecipeStructuredData(result.url)) index to result else null
                            }
                        }.awaitAll()
                    }
                    val validated = checked.filterNotNull().sortedBy { it.first }.map { it.second }
                    val recipePages = (validated + candidates.filterNot(validated::contains))
                        .distinctBy { it.url }.take(8)
                    if (recipePages.isNotEmpty()) return@withContext recipePages
                    lastError = IllegalStateException(
                        "No useful recipe links were found. Try a dish or ingredient, or import a recipe URL directly."
                    )
                } else {
                    lastError = IllegalStateException("Search provider responded, but its links could not be identified.")
                }
            } catch (e: Exception) {
                lastError = e
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException(
            lastError?.message ?: "Online search failed. Try another search or import a recipe URL directly.",
            lastError
        )
    }

    private suspend fun hasRecipeStructuredData(pageUrl: String): Boolean = withContext(Dispatchers.IO) {
        val connection = try {
            (URL(pageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
        } catch (_: Exception) { return@withContext false }

        fun containsRecipe(value: Any?): Boolean = when (value) {
            is JSONObject -> {
                val type = value.opt("@type")?.toString().orEmpty()
                type.contains("recipe", ignoreCase = true) ||
                    (0 until value.length()).any { index ->
                        containsRecipe(value.opt(value.names()?.optString(index).orEmpty()))
                    }
            }
            is JSONArray -> (0 until value.length()).any { containsRecipe(value.opt(it)) }
            else -> false
        }

        try {
            if (connection.responseCode !in 200..299) return@withContext false
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val scripts = Regex("""(?is)<script\b([^>]*)>(.*?)</script>""").findAll(html)
            scripts.any { match ->
                val attributes = match.groupValues[1]
                val type = Regex("""(?is)\btype\s*=\s*["']([^"']+)["']""")
                    .find(attributes)?.groupValues?.get(1).orEmpty()
                if (!type.equals("application/ld+json", ignoreCase = true)) {
                    false
                } else {
                    val json = match.groupValues[2].trim()
                    val value = try {
                        JSONObject(json)
                    } catch (_: Exception) {
                        try { JSONArray(json) } catch (_: Exception) { null }
                    }
                    containsRecipe(value)
                }
            }
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    suspend fun fetchImage(pageUrl: String): String? = withContext(Dispatchers.IO) {
        val connection = try {
            (URL(pageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
        } catch (_: Exception) { return@withContext null }
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val metaTags = Regex("""(?is)<meta\b[^>]*>""").findAll(html).map { it.value }.toList()
            fun attribute(tag: String, name: String): String? =
                Regex("""(?is)\b$name\s*=\s*["']([^"']+)["']""").find(tag)?.groupValues?.get(1)
            val image = metaTags.firstNotNullOfOrNull { tag ->
                val property = (attribute(tag, "property") ?: attribute(tag, "name")).orEmpty().lowercase()
                if (property in listOf("og:image", "og:image:url", "twitter:image", "twitter:image:src")) {
                    attribute(tag, "content")?.trim()?.takeIf { it.isNotBlank() }
                } else null
            } ?: Regex("""(?is)["']image["']\s*:\s*["']([^"']+)["']""")
                .find(html)?.groupValues?.get(1)
            val decoded = image?.replace("&amp;", "&", ignoreCase = true)
                ?.replace("&#x2F;", "/", ignoreCase = true)?.trim()
                ?: return@withContext null
            val resolved = try { URL(URL(pageUrl), decoded).toString() } catch (_: Exception) { return@withContext null }
            if (resolved.startsWith("https://") || resolved.startsWith("http://")) resolved else null
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeHtml(value: String): String = value
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&#x27;", "'", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
}
private fun recipeToJson(r: Recipe) = JSONObject().apply {
    put("id", r.id); put("title", r.title); put("description", r.description)
    put("ingredients", r.ingredients); put("instructions", r.instructions)
    put("sourceUrl", r.sourceUrl); put("prepTime", r.prepTime)
    put("cookTime", r.cookTime); put("servings", r.servings)
    put("calories", r.calories); put("protein", r.protein)
    put("carbs", r.carbs); put("fat", r.fat)
}

private fun recipeFromJson(o: JSONObject) = Recipe(
    id = o.optString("id", UUID.randomUUID().toString()),
    title = o.optString("title", "Untitled recipe"),
    description = o.optString("description"), ingredients = o.optString("ingredients"),
    instructions = o.optString("instructions"), sourceUrl = o.optString("sourceUrl"),
    prepTime = o.optString("prepTime"), cookTime = o.optString("cookTime"),
    servings = o.optString("servings", "2"),
    calories = o.optString("calories"), protein = o.optString("protein"),
    carbs = o.optString("carbs"), fat = o.optString("fat")
)

class MainActivity : ComponentActivity() {
    private lateinit var store: RecipeStore

    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) try {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(store.backupJson()) }
                ?: throw IllegalStateException("Could not write the backup file.")
            Toast.makeText(this, "PantryPal backup saved", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) try {
            val recipes = contentResolver.openInputStream(uri)?.bufferedReader()?.use { store.restoreJson(it.readText()) }
                ?: throw IllegalStateException("Could not read the selected file.")
            val merged = (store.load().associateBy { it.id } + recipes.associateBy { it.id }).values.toList()
            store.save(merged)
            Toast.makeText(this, "Restored ${recipes.size} recipes", Toast.LENGTH_LONG).show()
            recreate()
        } catch (e: Exception) {
            Toast.makeText(this, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RecipeStore(this)
        val sharedText = intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val sharedUrl = Regex("https?://\\S+", RegexOption.IGNORE_CASE).find(sharedText)?.value.orEmpty()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = PantryOrange, onPrimary = PantryInk)) {
                PantryPalApp(store.load(), store::save, store.loadPlan(), store::savePlan,
                    onBackup = { createBackup.launch("PantryPal-backup.json") },
                    onRestore = { openBackup.launch(arrayOf("application/json", "text/json", "text/plain")) },
                    initialSharedText = sharedText, initialSharedUrl = sharedUrl)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PantryPalApp(
    initialRecipes: List<Recipe>, persist: (List<Recipe>) -> Unit,
    initialPlan: List<PlannedMeal>, persistPlan: (List<PlannedMeal>) -> Unit,
    onBackup: () -> Unit, onRestore: () -> Unit,
    initialSharedText: String = "", initialSharedUrl: String = ""
) {
    var recipes by remember { mutableStateOf(initialRecipes) }
    var mealPlan by remember { mutableStateOf(initialPlan) }
    var showPlanner by remember { mutableStateOf(false) }
    var showShopping by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Recipe?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(initialSharedText.isNotBlank()) }
    var showRecipeSearch by remember { mutableStateOf(false) }
    var selectedBrowseSite by remember { mutableStateOf<BrowseSite?>(null) }
    var browseThemeQuery by remember { mutableStateOf("") }
    var browseFilter by remember { mutableStateOf("") }
    var browsingUrl by remember { mutableStateOf<String?>(null) }
    var homeSection by remember { mutableStateOf("My recipes") }
    var selected by remember { mutableStateOf<Recipe?>(null) }
    val context = LocalContext.current

    fun update(next: List<Recipe>) {
        recipes = next
        persist(next)
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color(0xFFF7F7F5),
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("PantryPal", fontWeight = FontWeight.ExtraBold, color = PantryInk,
                            style = MaterialTheme.typography.titleLarge)
                        Text("YOUR PERSONAL RECIPE BOX", color = androidx.compose.ui.graphics.Color(0xFF6B7280),
                            style = MaterialTheme.typography.labelSmall, letterSpacing = 1.2f.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color(0xFFF7F7F5))
            )
        }
    ) { inset ->
        Column(
            Modifier.fillMaxSize().padding(inset).padding(horizontal = 18.dp)
        ) {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = PantryInk),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("YOUR KITCHEN, ORGANISED", color = PantryOrange,
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text("What are we cooking?", color = androidx.compose.ui.graphics.Color.White,
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        Text("Save favourites or discover something new.",
                            color = androidx.compose.ui.graphics.Color(0xFFD1D5DB), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = { selectedBrowseSite = null; showRecipeSearch = true },
                        shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PantryOrange, contentColor = PantryInk),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text("Explore", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().background(
                    androidx.compose.ui.graphics.Color(0xFFECECE8), RoundedCornerShape(16.dp)
                ).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("My recipes", "Browse sites").forEach { section ->
                    val active = homeSection == section
                    Button(
                        onClick = { homeSection = section },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (active) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Transparent,
                            contentColor = if (active) PantryInk else androidx.compose.ui.graphics.Color(0xFF6B7280)
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = if (active) 1.dp else 0.dp),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Text(if (section == "My recipes") "My recipes (${recipes.size})" else "Browse sites",
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }

            key(recipes.map { it.id }) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp)
            ) {
                if (homeSection == "My recipes") {
                    item {
                        OutlinedTextField(
                            value = search, onValueChange = { search = it },
                            label = { Text("Search saved recipes") },
                            placeholder = { Text("Recipe name or ingredient") },
                            leadingIcon = { Text("⌕", style = MaterialTheme.typography.headlineSmall, color = PantryInk) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(15.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PantryOrange,
                                focusedLabelColor = PantryInk,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.White,
                                focusedContainerColor = androidx.compose.ui.graphics.Color.White
                            )
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { editing = null; showForm = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(13.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PantryOrange, contentColor = PantryInk),
                                contentPadding = PaddingValues(vertical = 11.dp)
                            ) { Text("+ Add recipe", fontWeight = FontWeight.Bold) }
                            OutlinedButton(
                                onClick = { showImport = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(13.dp),
                                contentPadding = PaddingValues(vertical = 11.dp)
                            ) { Text("Import from link") }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { showPlanner = true }, modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(13.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                                Text("Meal planner")
                            }
                            OutlinedButton(onClick = { showShopping = true }, modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(13.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                                Text("Shopping list")
                            }
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("YOUR RECIPE BOX", style = MaterialTheme.typography.labelMedium,
                                color = androidx.compose.ui.graphics.Color(0xFF6B7280),
                                fontWeight = FontWeight.Bold, letterSpacing = 0.8f.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                                TextButton(onClick = onBackup, colors = ButtonDefaults.textButtonColors(contentColor = PantryInk)) {
                                    Text("Backup", style = MaterialTheme.typography.labelMedium)
                                }
                                TextButton(onClick = onRestore, colors = ButtonDefaults.textButtonColors(contentColor = PantryInk)) {
                                    Text("Restore", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    val visible = recipes.filter {
                        it.title.contains(search, true) || it.ingredients.contains(search, true)
                    }
                    if (visible.isEmpty()) {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 22.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(if (recipes.isEmpty()) "Your recipe box is empty" else "No matching recipes",
                                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = PantryInk)
                                Text(if (recipes.isEmpty()) "Add a recipe, import a link, or explore the Browse tab."
                                    else "Try another search.", color = androidx.compose.ui.graphics.Color(0xFF6B7280))
                                if (recipes.isEmpty()) {
                                    Button(
                                        onClick = { homeSection = "Browse sites" },
                                        shape = RoundedCornerShape(13.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = PantryOrange, contentColor = PantryInk)
                                    ) { Text("Browse recipe sites") }
                                }
                            }
                        }
                    } else {
                        items(visible, key = { it.id }) { recipe ->
                            SavedRecipeCard(recipe = recipe, onClick = { selected = recipe })
                        }
                    }
                } else {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)) {
                            Text("FIND YOUR NEXT FAVOURITE", style = MaterialTheme.typography.labelMedium,
                                color = PantryOrange, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
                            Text("Browse recipe websites", style = MaterialTheme.typography.titleLarge,
                                color = PantryInk, fontWeight = FontWeight.ExtraBold)
                            Text("Pick a site, search for a dish, then preview it before adding it to your recipe box.",
                                style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color(0xFF6B7280))
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = browseFilter,
                            onValueChange = { browseFilter = it },
                            placeholder = { Text("Find a cuisine, category or website") },
                            leadingIcon = { Text("⌕", color = PantryInk, style = MaterialTheme.typography.titleLarge) },
                            singleLine = true,
                            shape = RoundedCornerShape(15.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PantryOrange,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.White,
                                focusedContainerColor = androidx.compose.ui.graphics.Color.White
                            )
                        )
                    }
                    items(
                        browseCategories.filter { category ->
                            browseFilter.isBlank() ||
                                category.title.contains(browseFilter, ignoreCase = true) ||
                                category.subtitle.contains(browseFilter, ignoreCase = true) ||
                                browseSites.any { it.category == category.title && it.name.contains(browseFilter, ignoreCase = true) }
                        },
                        key = { it.title }
                    ) { category ->
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(category.emoji, style = MaterialTheme.typography.headlineMedium)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(category.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = PantryInk)
                                    Text(category.subtitle, style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color(0xFF6B7280))
                                }
                            }
                            browseSites.filter { it.category == category.title }.chunked(2).forEach { rowSites ->
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                    rowSites.forEach { site ->
                                        Card(
                                            onClick = { browsingUrl = "https://${site.domain}${site.path}" },
                                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp),
                                            colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)
                                        ) {
                                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(site.emoji, style = MaterialTheme.typography.headlineSmall)
                                                Text(site.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                                                    color = PantryInk, maxLines = 2)
                                            }
                                        }
                                    }
                                    if (rowSites.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    item {
                        OutlinedButton(
                            onClick = { selectedBrowseSite = null; showRecipeSearch = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) { Text("Search all websites") }
                    }
                }
            }
            }
        }
    }

    browsingUrl?.let { startUrl ->
        RecipeBrowserDialog(
            initialUrl = startUrl,
            onDismiss = { browsingUrl = null },
            onImport = { recipe ->
                browsingUrl = null
                selected = recipe
            }
        )
    }

    if (showPlanner) MealPlannerDialog(recipes, mealPlan, { showPlanner = false }) { next ->
        mealPlan = next
        persistPlan(next)
    }
    if (showShopping) ShoppingListDialog(recipes, mealPlan, { showShopping = false })
    if (showRecipeSearch) RecipeSearchDialog(
        site = selectedBrowseSite,
        initialQuery = browseThemeQuery,
        onDismiss = { showRecipeSearch = false; selectedBrowseSite = null; browseThemeQuery = "" },
        onImport = { recipe ->
            // Online search only opens a preview. Save only when the user taps Add to box.
            showRecipeSearch = false
            selectedBrowseSite = null
            selected = recipe
        }
    )
    if (showForm) RecipeFormDialog(
        recipe = editing,
        onDismiss = { showForm = false; editing = null },
        onSave = { recipe ->
            val alreadySaved = recipes.any { it.id == recipe.id }
            if (editing != null && !alreadySaved) {
                // Editing an imported preview should return to preview; Add to box remains the save action.
                selected = recipe
            } else {
                val next = if (alreadySaved) recipes.map { if (it.id == recipe.id) recipe else it }
                    else listOf(recipe) + recipes
                update(next)
            }
            showForm = false
            editing = null
        }
    )
    if (showImport) ImportDialog(
        initialAddress = initialSharedUrl,
        sharedText = initialSharedText,
        onDismiss = { showImport = false },
        onManual = { showImport = false; editing = null; showForm = true },
        onImport = { recipe ->
            // Fetch and preview the recipe without saving it to the local box.
            showImport = false
            selected = recipe
        }
    )
    selected?.let { recipe ->
        RecipeDetailDialog(
            recipe = recipe,
            onDismiss = { selected = null },
            onImportAnother = { selected = null; showImport = true },
            onEdit = { editing = recipe; selected = null; showForm = true },
            onAdd = {
                update(listOf(recipe) + recipes.filterNot { it.id == recipe.id })
                selected = null
            },
            onDelete = {
                update(recipes.filterNot { it.id == recipe.id })
                val nextPlan = mealPlan.filterNot { it.recipeId == recipe.id }
                mealPlan = nextPlan
                persistPlan(nextPlan)
                selected = null
            }
        )
    }
}

@Composable
private fun SavedRecipeCard(recipe: Recipe, onClick: () -> Unit) {
    val imageUrl by produceState<String?>(initialValue = null, key1 = recipe.sourceUrl) {
        value = if (recipe.sourceUrl.isNotBlank()) RecipeWebSearch().fetchImage(recipe.sourceUrl) else null
    }
    val title = recipe.title.lowercase()
    val foodEmoji = when {
        listOf("fish", "salmon", "tuna", "prawn", "shrimp", "seafood").any { it in title } -> "🐟"
        listOf("chicken", "turkey", "roast", "beef", "steak", "pork", "meat").any { it in title } -> "🍗"
        listOf("cake", "bake", "cookie", "brownie", "dessert", "pudding").any { it in title } -> "🍰"
        listOf("salad", "vegetable", "veggie", "vegan").any { it in title } -> "🥗"
        listOf("pasta", "spaghetti", "lasagne", "noodle").any { it in title } -> "🍝"
        listOf("soup", "stew", "curry", "chilli").any { it in title } -> "🍲"
        listOf("bread", "toast", "sandwich", "bagel").any { it in title } -> "🥪"
        listOf("rice", "bowl").any { it in title } -> "🍚"
        else -> "🍽️"
    }
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().height(148.dp)
                    .background(androidx.compose.ui.graphics.Color(0xFFFFF1D6)),
                contentAlignment = Alignment.Center
            ) {
                if (!imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = recipe.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(foodEmoji, style = MaterialTheme.typography.displayLarge)
                }
                if (recipe.sourceUrl.isNotBlank()) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.94f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text("IMPORTED", modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = PantryInk)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(recipe.title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold, color = PantryInk, maxLines = 2)
                if (recipe.description.isNotBlank()) {
                    Text(recipe.description, maxLines = 2,
                        color = androidx.compose.ui.graphics.Color(0xFF6B7280),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (listOf(recipe.calories, recipe.protein, recipe.carbs, recipe.fat).any { it.isNotBlank() }) {
                    Text(listOfNotNull(
                        recipe.calories.takeIf { it.isNotBlank() }?.let { "$it kcal" },
                        recipe.protein.takeIf { it.isNotBlank() }?.let { "P $it g" },
                        recipe.carbs.takeIf { it.isNotBlank() }?.let { "C $it g" },
                        recipe.fat.takeIf { it.isNotBlank() }?.let { "F $it g" }
                    ).joinToString("  ·  "), style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold, color = PantryInk)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (recipe.prepTime.isNotBlank()) {
                        Surface(color = androidx.compose.ui.graphics.Color(0xFFFFF1D6), shape = RoundedCornerShape(8.dp)) {
                            Text("⏱ ${recipe.prepTime}", modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelSmall, color = PantryInk)
                        }
                    }
                    if (recipe.cookTime.isNotBlank()) {
                        Surface(color = androidx.compose.ui.graphics.Color(0xFFF3F4F6), shape = RoundedCornerShape(8.dp)) {
                            Text("🔥 ${recipe.cookTime}", modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelSmall, color = PantryInk)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("View recipe  →", style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold, color = PantryOrange)
                }
            }
        }
    }
}

@Composable
private fun RecipeFormDialog(recipe: Recipe?, onDismiss: () -> Unit, onSave: (Recipe) -> Unit) {
    var title by remember(recipe?.id) { mutableStateOf(recipe?.title ?: "") }
    var description by remember(recipe?.id) { mutableStateOf(recipe?.description ?: "") }
    var ingredients by remember(recipe?.id) { mutableStateOf(recipe?.ingredients ?: "") }
    var instructions by remember(recipe?.id) { mutableStateOf(recipe?.instructions ?: "") }
    var servings by remember(recipe?.id) { mutableStateOf(recipe?.servings ?: "2") }
    var calories by remember(recipe?.id) { mutableStateOf(recipe?.calories ?: "") }
    var protein by remember(recipe?.id) { mutableStateOf(recipe?.protein ?: "") }
    var carbs by remember(recipe?.id) { mutableStateOf(recipe?.carbs ?: "") }
    var fat by remember(recipe?.id) { mutableStateOf(recipe?.fat ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (recipe == null) "Add recipe" else "Edit recipe") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Recipe name *") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("Description") })
                OutlinedTextField(servings, { servings = it }, label = { Text("Servings") }, singleLine = true)
                Text("Nutrition per serving (optional)", fontWeight = FontWeight.Bold, color = PantryInk)
                Text("Use values from the recipe or a nutrition label. Missing values aren't guessed.", style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color(0xFF6B7280))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(calories, { calories = it }, label = { Text("Calories (kcal)") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(protein, { protein = it }, label = { Text("Protein (g)") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(carbs, { carbs = it }, label = { Text("Carbs (g)") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(fat, { fat = it }, label = { Text("Fat (g)") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(ingredients, { ingredients = it }, label = { Text("Ingredients (one per line)") }, minLines = 3)
                OutlinedTextField(instructions, { instructions = it }, label = { Text("Instructions") }, minLines = 4)
            }
        },
        confirmButton = {
            Button(enabled = title.isNotBlank(), onClick = {
                val base = recipe ?: Recipe(title = title.trim())
                onSave(base.copy(title = title.trim(), description = description.trim(), ingredients = ingredients.trim(),
                    instructions = instructions.trim(), servings = servings.trim(),
                    calories = calories.trim(), protein = protein.trim(), carbs = carbs.trim(), fat = fat.trim()))
            }) { Text("Save recipe") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ImportDialog(
    initialAddress: String = "",
    sharedText: String = "",
    onDismiss: () -> Unit,
    onManual: () -> Unit,
    onImport: (Recipe) -> Unit
) {
    fun removeLinks(text: String): String = text
        .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    var address by remember { mutableStateOf(initialAddress) }
    var caption by remember { mutableStateOf(removeLinks(sharedText)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val socialSource = when {
        address.contains("facebook.com", true) || address.contains("fb.watch", true) ||
            sharedText.contains("facebook.com", true) || sharedText.contains("fb.watch", true) -> "Facebook"
        address.contains("instagram.com", true) || sharedText.contains("instagram.com", true) -> "Instagram"
        address.contains("tiktok.com", true) || sharedText.contains("tiktok.com", true) -> "TikTok"
        else -> null
    }
    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(if (socialSource != null) "Import from $socialSource" else "Import a recipe") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (socialSource == "Facebook")
                        "Facebook often hides post content behind login or app-only pages, so automatic link import may fail. If the recipe is visible in the post, copy its text and paste it below. PantryPal will make an editable draft from the text."
                    else if (socialSource != null)
                        "Instagram and TikTok often share only a link, and may block automatic recipe extraction. Try the link, or paste the post caption below to make a recipe draft."
                    else if (sharedText.isNotBlank())
                        "Shared from another app. PantryPal will try the link first. If the page cannot be read, you can use the shared text to make a draft."
                    else
                        "Paste a recipe webpage URL. PantryPal looks for structured recipe data on the page."
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it.trimStart(); error = null },
                    label = { Text("Recipe or social post URL") },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (sharedText.isNotBlank() || socialSource != null) {
                    OutlinedTextField(
                        value = caption,
                        onValueChange = { caption = it; error = null },
                        label = { Text("Post caption, ingredients or steps") },
                        placeholder = { Text("Paste the caption or recipe text here…") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        when (socialSource) {
                            "Facebook" -> "Tip: open the Facebook post, tap its menu and choose Copy text or Copy link where available. Paste the actual recipe text here; a link alone may not contain the recipe. Photos with text are not read automatically."
                            "Instagram", "TikTok" -> "Open the post, copy its caption or recipe text, then paste it here. PantryPal cannot reliably read text baked into a video."
                            else -> "Paste the recipe text here. Photos and text baked into videos are not read automatically."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        enabled = caption.isNotBlank(),
                        onClick = {
                            val cleaned = caption.trim()
                            val titleCandidate = cleaned.lineSequence()
                                .map { it.trim().trim('-', '•', '|', '#') }
                                .firstOrNull { it.isNotBlank() && !it.startsWith("http", true) }
                                ?.take(80)
                            val source = address.ifBlank {
                                Regex("https?://\\S+", RegexOption.IGNORE_CASE).find(sharedText)?.value.orEmpty()
                            }
                            val sourceName = when {
                                source.contains("facebook.com", true) || source.contains("fb.watch", true) -> "Facebook"
                                source.contains("instagram.com", true) -> "Instagram"
                                source.contains("tiktok.com", true) -> "TikTok"
                                else -> "shared post"
                            }
                            val recipeLines = cleaned.lines().map { it.trim() }
                                .filter { it.isNotBlank() && !it.startsWith("http", true) }
                            val ingredientPattern = Regex(
                                """^(?:[-•*]\\s*)?(?:\\d+(?:[./]\\d+)?\\s*)?(?:g|kg|ml|l|tsp|tbsp|teaspoons?|tablespoons?|cups?|oz|lb|cloves?|packets?|slices?|cans?|pinch(?:es)?|handfuls?)\\b""",
                                RegexOption.IGNORE_CASE
                            )
                            val stepPattern = Regex(
                                """^(?:(?:step\\s*)?\\d+[.)]?\\s+|[-•*]\\s+)?(?:preheat|heat|mix|stir|add|chop|slice|pour|bake|fry|boil|simmer|cook|serve|combine|place|whisk|blend|season|spread|drain|cook|then\\b)""",
                                RegexOption.IGNORE_CASE
                            )
                            val ingredients = recipeLines.filter { ingredientPattern.containsMatchIn(it) || it.startsWith("ingredient", true) }
                            val steps = recipeLines.filter { stepPattern.containsMatchIn(it) }
                            val extractedIngredients = ingredients.joinToString("\\n")
                            val extractedSteps = steps.joinToString("\\n")
                            onImport(
                                Recipe(
                                    title = titleCandidate?.takeIf { it.isNotBlank() } ?: "Recipe from $sourceName",
                                    description = "Draft from social post text. Check the extracted ingredients, quantities and cooking steps against the original post.",
                                    ingredients = extractedIngredients,
                                    instructions = if (extractedSteps.isNotBlank()) extractedSteps else cleaned,
                                    sourceUrl = source
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Make draft from caption / text") }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !loading && address.isNotBlank(), onClick = {
                loading = true
                error = null
                scope.launch {
                    try {
                        onImport(RecipeImporter().import(address))
                    } catch (e: Exception) {
                        error = (e.message ?: "Automatic import failed.") +
                            if (socialSource != null) " Paste the caption or recipe text above to create a draft instead." else " Try manual entry."
                    } finally {
                        loading = false
                    }
                }
            }) { Text(if (loading) "Importing…" else "Try link import") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onManual) { Text("Enter manually") }
                TextButton(enabled = !loading, onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun RecipeSearchDialog(site: BrowseSite? = null, initialQuery: String = "", onDismiss: () -> Unit, onImport: (Recipe) -> Unit) {
    var query by remember(site?.domain, initialQuery) {
        mutableStateOf(if (site != null) randomBrowseQueries.random() else initialQuery)
    }
    var results by remember { mutableStateOf<List<RecipeSearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var importingUrl by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedFilter by remember(site?.domain) { mutableStateOf("All") }
    var selectedFoodPhoto by remember { mutableStateOf<Uri?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        selectedFoodPhoto = uri
        if (uri != null && query.isBlank()) {
            error = "Photo selected. Add a dish name or ingredients below to search for matching recipes."
        }
    }
    val scope = rememberCoroutineScope()

    fun loadResults(term: String, shuffle: Boolean, filter: String = selectedFilter) {
        if (term.isBlank() || searching || importingUrl != null) return
        searching = true
        error = null
        results = emptyList()
        scope.launch {
            try {
                val filterWords = when (filter) {
                    "Quick" -> "quick easy 30 minute"
                    "Budget" -> "budget cheap affordable"
                    "High protein" -> "high protein"
                    "Vegetarian" -> "vegetarian meat free"
                    else -> ""
                }
                val searchTerm = listOf(term.trim(), filterWords).filter { it.isNotBlank() }.joinToString(" ")
                val found = RecipeWebSearch().search(searchTerm, site?.domain)
                results = if (shuffle) found.shuffled().take(10) else found
                if (results.isEmpty()) error = "No recipes found on " + (site?.name ?: "the supported recipe sites") + ". Try another search."
            } catch (e: Exception) {
                error = e.message ?: "Search failed. Try again."
            } finally {
                searching = false
            }
        }
    }

    LaunchedEffect(site?.domain) {
        if (site != null) loadResults(query, true)
    }

    AlertDialog(
        onDismissRequest = { if (!searching && importingUrl == null) onDismiss() },
        title = { Text(site?.let { "Browse ${it.name}" } ?: "Search recipes") },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = androidx.compose.ui.graphics.Color(0xFFFFF3D6),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🍽️", style = MaterialTheme.typography.headlineMedium)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Find something delicious", fontWeight = FontWeight.ExtraBold, color = PantryInk)
                            Text(
                                site?.let { "Search ${it.name} or try a different dish. Preview results before saving." }
                                    ?: "Choose a food photo for reference, then search by the dish or ingredients to find recipe pages. No AI service or photo upload is used.",
                                style = MaterialTheme.typography.bodySmall,
                                color = PantryInk.copy(alpha = 0.78f)
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = { photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (selectedFoodPhoto == null) "📷 Choose a food photo" else "📷 Change food photo") }
                selectedFoodPhoto?.let { photo ->
                    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFFFFBF2))) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AsyncImage(model = photo, contentDescription = "Selected food photo", contentScale = ContentScale.Crop, modifier = Modifier.size(88.dp).clip(RoundedCornerShape(10.dp)))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Photo selected", fontWeight = FontWeight.Bold, color = PantryInk)
                                Text("Use it as a reference, then type the dish or ingredients below. This version keeps the photo on your device and does not use AI image recognition.", style = MaterialTheme.typography.bodySmall, color = PantryInk.copy(alpha = 0.8f))
                                TextButton(onClick = { selectedFoodPhoto = null }) { Text("Remove photo") }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Dish or ingredient") },
                    placeholder = { Text(if (site == null) "e.g. chicken curry" else "Search ${site.name}, e.g. chicken curry") },
                    leadingIcon = { Text("⌕", color = PantryInk, style = MaterialTheme.typography.titleLarge) },
                    singleLine = true,
                    shape = RoundedCornerShape(15.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PantryOrange,
                        focusedLabelColor = PantryInk,
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.White,
                        focusedContainerColor = androidx.compose.ui.graphics.Color.White
                    )
                )
                Text("Narrow your search", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = PantryInk)
                listOf("All", "Quick", "Budget", "High protein", "Vegetarian").chunked(3).forEach { filterRow ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        filterRow.forEach { filter ->
                            FilterChip(
                                selected = selectedFilter == filter,
                                onClick = {
                                    selectedFilter = filter
                                    if (query.isNotBlank()) loadResults(query, false, filter)
                                },
                                label = { Text(filter) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = androidx.compose.ui.graphics.Color(0xFFFFE7B0),
                                    selectedLabelColor = PantryInk
                                )
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        enabled = query.isNotBlank() && !searching && importingUrl == null,
                        onClick = { loadResults(query, false) },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (searching) "Searching…" else "Search recipes") }
                    if (site != null) {
                        OutlinedButton(
                            enabled = !searching && importingUrl == null,
                            onClick = {
                                query = randomBrowseQueries.filterNot { it == query }.randomOrNull()
                                    ?: randomBrowseQueries.random()
                                loadResults(query, true)
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Shuffle 10") }
                    }
                }
                if (searching || importingUrl != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                results.forEach { result ->
                    var previewImage by remember(result.url) { mutableStateOf<String?>(null) }
                    LaunchedEffect(result.url) {
                        previewImage = RecipeWebSearch().fetchImage(result.url)
                    }
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFFFFBF2))
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            if (previewImage != null) {
                                AsyncImage(
                                    model = previewImage,
                                    contentDescription = "Food preview for ${result.title}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(100.dp).clip(RoundedCornerShape(10.dp))
                                )
                            } else {
                                Box(
                                    Modifier.size(100.dp).clip(RoundedCornerShape(10.dp))
                                        .background(androidx.compose.ui.graphics.Color(0xFFF3E8D0)),
                                    contentAlignment = Alignment.Center
                                ) { Text("🍽️", style = MaterialTheme.typography.headlineMedium) }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    result.title,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = PantryInk,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 3
                                )
                                Surface(
                                    color = androidx.compose.ui.graphics.Color(0xFFFFE7B0),
                                    shape = RoundedCornerShape(7.dp)
                                ) {
                                    Text(
                                        Uri.parse(result.url).host.orEmpty().removePrefix("www."),
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PantryInk,
                                        maxLines = 1
                                    )
                                }
                                Button(
                                    enabled = importingUrl == null && !searching,
                                    onClick = {
                                        importingUrl = result.url
                                        error = null
                                        scope.launch {
                                            try { onImport(RecipeImporter().import(result.url)) }
                                            catch (e: Exception) { error = e.message ?: "Could not import this recipe. Try importing its URL directly." }
                                            finally { importingUrl = null }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(if (importingUrl == result.url) "Loading preview…" else "Preview recipe") }
                            }
                        }
                    }
                }
                Text(
                    "PantryPal reads recipe details where the website makes them available. Some sites block importing; recipes are not saved until you tap Add to box.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(enabled = importingUrl == null && !searching, onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun NutritionValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color(0xFF6B7280))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = PantryInk)
    }
}

@Composable
private fun RecipeDetailDialog(recipe: Recipe, onDismiss: () -> Unit, onImportAnother: () -> Unit, onAdd: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var cookingMode by remember(recipe.id) { mutableStateOf(false) }
    var servings by remember(recipe.id) { mutableIntStateOf(recipe.servings.toIntOrNull()?.coerceAtLeast(1) ?: 2) }
    val ingredients = remember(recipe.ingredients, servings, recipe.servings) {
        scaleIngredients(recipe.ingredients, servings, recipe.servings.toIntOrNull()?.coerceAtLeast(1) ?: 2)
    }
    val steps = remember(recipe.instructions) { recipe.instructions.lines().map { it.trim() }.filter { it.isNotBlank() } }
    val checkedSteps = remember(recipe.id, cookingMode) { mutableStateMapOf<Int, Boolean>() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).widthIn(max = 560.dp),
            shape = RoundedCornerShape(28.dp),
            color = androidx.compose.ui.graphics.Color(0xFFFFFEFA),
            tonalElevation = 8.dp,
            shadowElevation = 16.dp
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(PantryOrange)
                        .padding(start = 22.dp, end = 12.dp, top = 20.dp, bottom = 20.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            "YOUR RECIPE",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = PantryInk.copy(alpha = 0.72f),
                            letterSpacing = 1.6.sp
                        )
                        Text(
                            recipe.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = PantryInk
                        )
                        if (recipe.prepTime.isNotBlank() || recipe.cookTime.isNotBlank()) {
                            Text(
                                listOfNotNull(
                                    recipe.prepTime.takeIf { it.isNotBlank() }?.let { "Prep $it" },
                                    recipe.cookTime.takeIf { it.isNotBlank() }?.let { "Cook $it" }
                                ).joinToString("  ·  "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = PantryInk.copy(alpha = 0.8f)
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Text("✕", color = PantryInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (recipe.description.isNotBlank()) {
                        Text(
                            recipe.description,
                            style = MaterialTheme.typography.bodyLarge,
                            color = PantryInk.copy(alpha = 0.9f)
                        )
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFFFF3D6)),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("SERVINGS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = PantryInk.copy(alpha = 0.65f))
                                Text("$servings portions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = PantryInk)
                            }
                            IconButton(onClick = { servings = (servings - 1).coerceAtLeast(1) }) {
                                Text("−", fontSize = 24.sp, color = PantryInk)
                            }
                            Text("$servings", fontWeight = FontWeight.Bold, color = PantryInk)
                            IconButton(onClick = { servings = (servings + 1).coerceAtMost(100) }) {
                                Text("+", fontSize = 24.sp, color = PantryInk)
                            }
                        }
                    }

                    if (listOf(recipe.calories, recipe.protein, recipe.carbs, recipe.fat).any { it.isNotBlank() }) {
                        Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFF4F5F2)), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Nutrition per serving", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = PantryInk)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    NutritionValue("Calories", recipe.calories.takeIf { it.isNotBlank() }?.let { "$it kcal" } ?: "—", Modifier.weight(1f))
                                    NutritionValue("Protein", recipe.protein.takeIf { it.isNotBlank() }?.let { "$it g" } ?: "—", Modifier.weight(1f))
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    NutritionValue("Carbs", recipe.carbs.takeIf { it.isNotBlank() }?.let { "$it g" } ?: "—", Modifier.weight(1f))
                                    NutritionValue("Fat", recipe.fat.takeIf { it.isNotBlank() }?.let { "$it g" } ?: "—", Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    if (recipe.ingredients.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Ingredients", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = PantryInk)
                            Card(
                                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFF4F5F2)),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ingredients.lines().filter { it.isNotBlank() }.forEach { ingredient ->
                                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text("•", color = PantryOrange, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                                            Text(ingredient, modifier = Modifier.weight(1f), color = PantryInk, style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (recipe.instructions.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Method", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = PantryInk)
                                Text("Step by step", style = MaterialTheme.typography.bodySmall, color = PantryInk.copy(alpha = 0.65f))
                            }
                            Text("Cooking mode", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = PantryInk)
                            Switch(checked = cookingMode, onCheckedChange = { cookingMode = it })
                        }
                        if (cookingMode) {
                            steps.forEachIndexed { index, step ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (checkedSteps[index] == true) androidx.compose.ui.graphics.Color(0xFFE8F5E9)
                                        else androidx.compose.ui.graphics.Color(0xFFF4F5F2)
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Checkbox(
                                            checked = checkedSteps[index] == true,
                                            onCheckedChange = { checkedSteps[index] = it }
                                        )
                                        Column(modifier = Modifier.weight(1f).padding(top = 10.dp, end = 8.dp)) {
                                            Text("STEP ${index + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, color = PantryOrange)
                                            Text(step, color = PantryInk, style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                            }
                        } else {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFF4F5F2)),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text(
                                    recipe.instructions,
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    color = PantryInk,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }

                    if (recipe.sourceUrl.isNotBlank()) {
                        Text("Source: ${recipe.sourceUrl}", style = MaterialTheme.typography.bodySmall, color = PantryInk.copy(alpha = 0.6f))
                    }
                }

                HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFFE7E5DF))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onImportAnother,
                        contentPadding = PaddingValues(horizontal = 5.dp),
                        modifier = Modifier.weight(1.25f)
                    ) { Text("Import another", maxLines = 1, softWrap = false) }
                    TextButton(
                        onClick = onDelete,
                        contentPadding = PaddingValues(horizontal = 5.dp),
                        modifier = Modifier.weight(0.7f)
                    ) { Text("Delete", maxLines = 1, softWrap = false) }
                    Button(
                        onClick = onAdd,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1.0f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Add to box", maxLines = 1, softWrap = false) }
                    Button(
                        onClick = onEdit,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier.weight(0.8f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Edit", maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

@Composable
private fun RecipeBrowserDialog(initialUrl: String, onDismiss: () -> Unit, onImport: (Recipe) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importingPage by remember(initialUrl) { mutableStateOf(false) }
    var importError by remember(initialUrl) { mutableStateOf<String?>(null) }
    var currentUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
    var pageTitle by remember(initialUrl) { mutableStateOf("Recipe website") }
    var progress by remember(initialUrl) { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = androidx.compose.ui.graphics.Color.White
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(PantryInk)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            val browser = webView
                            if (browser != null && browser.canGoBack()) browser.goBack() else onDismiss()
                        },
                        enabled = canGoBack || currentUrl == initialUrl,
                        colors = ButtonDefaults.textButtonColors(contentColor = androidx.compose.ui.graphics.Color.White)
                    ) { Text("‹ Back") }
                    Column(Modifier.weight(1f)) {
                        Text(
                            pageTitle.ifBlank { "Recipe website" },
                            color = androidx.compose.ui.graphics.Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            currentUrl.removePrefix("https://").removePrefix("http://"),
                            color = androidx.compose.ui.graphics.Color(0xFFD1D5DB),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                    TextButton(
                        enabled = !importingPage && currentUrl.startsWith("http"),
                        onClick = {
                            val url = currentUrl
                            importingPage = true
                            importError = null
                            scope.launch {
                                try {
                                    val recipe = RecipeImporter().import(url)
                                    onImport(recipe)
                                } catch (e: Exception) {
                                    importError = e.message ?: "Couldn't import a recipe from this page."
                                } finally {
                                    importingPage = false
                                }
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = PantryOrange)
                    ) {
                        Text(if (importingPage) "Importing…" else "Import", fontWeight = FontWeight.Bold)
                    }
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = androidx.compose.ui.graphics.Color.White)
                    ) { Text("Done", fontWeight = FontWeight.Bold) }
                }
                importError?.let { message ->
                    Surface(color = androidx.compose.ui.graphics.Color(0xFFFFF3E0)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { importError = null }) { Text("Dismiss") }
                        }
                    }
                }
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = PantryOrange
                    )
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadsImagesAutomatically = true
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            // Keep touch gestures inside the website browser so parent Compose layouts
                            // cannot steal vertical swipes from pages with nested scrolling.
                            isNestedScrollingEnabled = true
                            isVerticalScrollBarEnabled = true
                            overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
                            setOnTouchListener { view, event ->
                                when (event.actionMasked) {
                                    android.view.MotionEvent.ACTION_DOWN,
                                    android.view.MotionEvent.ACTION_MOVE ->
                                        view.parent?.requestDisallowInterceptTouchEvent(true)
                                    android.view.MotionEvent.ACTION_UP,
                                    android.view.MotionEvent.ACTION_CANCEL ->
                                        view.parent?.requestDisallowInterceptTouchEvent(false)
                                }
                                false
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    currentUrl = url ?: currentUrl
                                    canGoBack = view?.canGoBack() == true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    currentUrl = url ?: currentUrl
                                    pageTitle = view?.title?.takeIf { it.isNotBlank() } ?: currentUrl
                                    canGoBack = view?.canGoBack() == true
                                    // Hide common ad containers, especially persistent bottom banners.
                                    view?.evaluateJavascript(
                                        """(function(){var s=document.createElement('style');s.textContent='ins.adsbygoogle,[id*=google_ads],[id*="sticky-ad"],[class*="sticky-ad"],[id*="bottom-ad"],[class*="bottom-ad"],[class*="ad-banner"],[data-testid*="ad-container"]{display:none!important;visibility:hidden!important;height:0!important;max-height:0!important;pointer-events:none!important}';document.documentElement.appendChild(s);})();""",
                                        null
                                    )
                                }

                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                                    val host = request?.url?.host?.lowercase().orEmpty()
                                    val adHosts = listOf(
                                        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
                                        "adservice.google.com", "adnxs.com", "taboola.com", "outbrain.com",
                                        "criteo.com", "amazon-adsystem.com", "ads-twitter.com"
                                    )
                                    if (adHosts.any { host == it || host.endsWith(".$it") }) {
                                        return android.webkit.WebResourceResponse(
                                            "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                                        )
                                    }
                                    return super.shouldInterceptRequest(view, request)
                                }

                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val target = request.url
                                    if (target.scheme == "http" || target.scheme == "https") return false
                                    return try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, target))
                                        true
                                    } catch (_: Exception) {
                                        true
                                    }
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                    pageTitle = view?.title?.takeIf { it.isNotBlank() } ?: pageTitle
                                }
                            }
                            webView = this
                            loadUrl(initialUrl)
                        }
                    },
                    update = { view ->
                        if (view.url == null) view.loadUrl(initialUrl)
                    },
                    onRelease = { view ->
                        view.stopLoading()
                        view.destroy()
                        if (webView === view) webView = null
                    }
                )
            }
        }
    }
}

private val plannerDays = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private val plannerMeals = listOf("Breakfast", "Lunch", "Dinner", "Snack")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MealPlannerDialog(recipes: List<Recipe>, plan: List<PlannedMeal>, onDismiss: () -> Unit, onSave: (List<PlannedMeal>) -> Unit) {
    var recipeId by remember { mutableStateOf(recipes.firstOrNull()?.id.orEmpty()) }
    var day by remember { mutableStateOf("Monday") }
    var mealType by remember { mutableStateOf("Dinner") }
    var servings by remember { mutableIntStateOf(2) }
    var recipeMenu by remember { mutableStateOf(false) }
    var dayMenu by remember { mutableStateOf(false) }
    var mealMenu by remember { mutableStateOf(false) }
    val selectedRecipe = recipes.firstOrNull { it.id == recipeId }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Weekly meal planner") }, text = {
        Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (recipes.isEmpty()) Text("Add a recipe before planning meals.") else {
                ExposedDropdownMenuBox(expanded = recipeMenu, onExpandedChange = { recipeMenu = it }) {
                    OutlinedTextField(selectedRecipe?.title ?: "Choose recipe", {}, readOnly = true, label = { Text("Recipe") }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = recipeMenu, onDismissRequest = { recipeMenu = false }) {
                        recipes.forEach { r -> DropdownMenuItem(text = { Text(r.title) }, onClick = { recipeId = r.id; recipeMenu = false; servings = r.servings.toIntOrNull()?.coerceAtLeast(1) ?: 2 }) }
                    }
                }
                ExposedDropdownMenuBox(expanded = dayMenu, onExpandedChange = { dayMenu = it }) {
                    OutlinedTextField(day, {}, readOnly = true, label = { Text("Day") }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = dayMenu, onDismissRequest = { dayMenu = false }) {
                        plannerDays.forEach { d -> DropdownMenuItem(text = { Text(d) }, onClick = { day = d; dayMenu = false }) }
                    }
                }
                ExposedDropdownMenuBox(expanded = mealMenu, onExpandedChange = { mealMenu = it }) {
                    OutlinedTextField(mealType, {}, readOnly = true, label = { Text("Meal") }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = mealMenu, onDismissRequest = { mealMenu = false }) {
                        plannerMeals.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { mealType = m; mealMenu = false }) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Servings", modifier = Modifier.weight(1f))
                    IconButton(onClick = { servings = (servings - 1).coerceAtLeast(1) }) { Text("−") }
                    Text("$servings")
                    IconButton(onClick = { servings = (servings + 1).coerceAtMost(100) }) { Text("+") }
                }
                Button(enabled = selectedRecipe != null, onClick = {
                    val r = selectedRecipe ?: return@Button
                    onSave(plan + PlannedMeal(recipeId = r.id, recipeTitle = r.title, day = day, mealType = mealType, servings = servings))
                }, modifier = Modifier.fillMaxWidth()) { Text("Add to plan") }
            }
            Text("Your week", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (plan.isEmpty()) Text("No meals planned yet.")
            plannerDays.forEach { d ->
                val entries = plan.filter { it.day == d }.sortedBy { plannerMeals.indexOf(it.mealType) }
                if (entries.isNotEmpty()) {
                    Text(d, fontWeight = FontWeight.Bold)
                    entries.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${p.mealType}: ${p.recipeTitle} · ${p.servings}", modifier = Modifier.weight(1f))
                            IconButton(onClick = { onSave(plan.filterNot { it.id == p.id }) }) { Text("×") }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { onSave(emptyList()) }) { Text("Clear week") } })
}

@Composable
private fun ShoppingListDialog(recipes: List<Recipe>, plan: List<PlannedMeal>, onDismiss: () -> Unit) {
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    val recipeMap = recipes.associateBy { it.id }
    val lines = remember(plan, recipes) {
        val all = mutableListOf<String>()
        plan.forEach { meal ->
            val recipe = recipeMap[meal.recipeId] ?: return@forEach
            val base = recipe.servings.toIntOrNull()?.coerceAtLeast(1) ?: 2
            recipe.ingredients.lines().filter { it.isNotBlank() }.forEach { ingredient ->
                all.add(scaleIngredient(ingredient.trim(), meal.servings, base))
            }
        }
        all.distinctBy { it.lowercase().replace(Regex("\\s+"), " ").trim() }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Shopping list") }, text = {
        Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${plan.size} planned meals", style = MaterialTheme.typography.labelLarge)
            if (lines.isEmpty()) Text("Plan some meals to build your shopping list.")
            lines.forEach { line ->
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(checked = checked[line] == true, onCheckedChange = { checked[line] = it })
                    Text(line, modifier = Modifier.padding(top = 12.dp).weight(1f))
                }
            }
            if (lines.isNotEmpty()) Text("${checked.count { it.value }} of ${lines.size} checked", style = MaterialTheme.typography.labelMedium)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { checked.clear() }) { Text("Uncheck all") } })
}

private fun scaleIngredients(text: String, targetServings: Int, baseServings: Int): String =
    text.lines().joinToString("\n") { scaleIngredient(it, targetServings, baseServings) }

private fun scaleIngredient(line: String, targetServings: Int, baseServings: Int): String {
    if (targetServings == baseServings || line.isBlank()) return line
    val multiplier = targetServings.toDouble() / baseServings.coerceAtLeast(1)
    val pattern = Regex("^\\s*(\\d+(?:\\.\\d+)?)(.*)$")
    val match = pattern.find(line) ?: return line
    val amount = match.groupValues[1].toDoubleOrNull() ?: return line
    val scaled = amount * multiplier
    val formatted = if (scaled % 1.0 == 0.0) scaled.toInt().toString() else String.format(java.util.Locale.UK, "%.2f", scaled).trimEnd('0').trimEnd('.')
    return formatted + match.groupValues[2]
}