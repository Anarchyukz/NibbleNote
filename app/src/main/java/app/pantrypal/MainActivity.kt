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

private data class RecipeSearchResult(val title: String, val url: String)

private class RecipeWebSearch {
    suspend fun search(query: String): List<RecipeSearchResult> = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "Enter a dish or ingredient to search for." }
        val encoded = URLEncoder.encode("\${query.trim()} recipe", "UTF-8")
        val connection = (URL("https://html.duckduckgo.com/html/?q=$encoded").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 12000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            setRequestProperty("Accept-Language", "en-GB,en;q=0.9")
        }
        try {
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val anchors = Regex("""(?is)<a[^>]*class=["'][^"']*result__a[^"']*["'][^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""").findAll(html)
            val results = anchors.mapNotNull { match ->
                var link = decodeHtml(match.groupValues[1])
                if (link.startsWith("//")) link = "https:$link"
                if (link.contains("duckduckgo.com/l/?")) {
                    val target = Regex("[?&]uddg=([^&]+)").find(link)?.groupValues?.get(1)
                    if (target != null) link = URLDecoder.decode(target, "UTF-8")
                }
                val title = decodeHtml(match.groupValues[2].replace(Regex("""(?is)<[^>]+>"""), "")).trim()
                if ((!link.startsWith("https://") && !link.startsWith("http://")) || link.contains("duckduckgo.com") || title.isBlank()) {
                    return@mapNotNull null
                }
                RecipeSearchResult(title, link)
            }.distinctBy { it.url }.take(12).toList()
            if (results.isEmpty()) throw IllegalStateException("No search results were returned. Try another search or import a recipe URL directly.")
            results
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
}

private fun recipeFromJson(o: JSONObject) = Recipe(
    id = o.optString("id", UUID.randomUUID().toString()),
    title = o.optString("title", "Untitled recipe"),
    description = o.optString("description"), ingredients = o.optString("ingredients"),
    instructions = o.optString("instructions"), sourceUrl = o.optString("sourceUrl"),
    prepTime = o.optString("prepTime"), cookTime = o.optString("cookTime"),
    servings = o.optString("servings", "2")
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
    var selected by remember { mutableStateOf<Recipe?>(null) }

    fun update(next: List<Recipe>) {
        recipes = next
        persist(next)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PantryPal", fontWeight = FontWeight.ExtraBold)
                        Text("Your recipes, your kitchen", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    TextButton(onClick = onBackup) { Text("Backup") }
                    TextButton(onClick = onRestore) { Text("Restore") }
                    TextButton(onClick = { showImport = true }) { Text("Import") }
                    TextButton(onClick = { showRecipeSearch = true }) { Text("Search") }
                    TextButton(onClick = { editing = null; showForm = true }) { Text("+ Add") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PantryOrange)
            )
        }
    ) { inset ->
        Column(Modifier.fillMaxSize().padding(inset).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                label = { Text("Search your recipes") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { showPlanner = true }, modifier = Modifier.weight(1f)) { Text("Meal planner") }
                OutlinedButton(onClick = { showShopping = true }, modifier = Modifier.weight(1f)) { Text("Shopping list") }
            }
            Spacer(Modifier.height(8.dp))
            Text("${recipes.size} saved ${if (recipes.size == 1) "recipe" else "recipes"}", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            val visible = recipes.filter {
                it.title.contains(search, true) || it.ingredients.contains(search, true)
            }
            if (visible.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(if (recipes.isEmpty()) "Your recipe box is empty" else "No matching recipes",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(if (recipes.isEmpty()) "Add a recipe or import one from a website." else "Try another search.")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { editing = null; showForm = true }) { Text("Add your first recipe") }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(visible, key = { it.id }) { recipe ->
                        Card(
                            onClick = { selected = recipe },
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFFFFBF2)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(recipe.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                if (recipe.description.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(recipe.description, maxLines = 2)
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (recipe.prepTime.isNotBlank()) Text("Prep ${recipe.prepTime}", style = MaterialTheme.typography.labelSmall)
                                    if (recipe.cookTime.isNotBlank()) Text("Cook ${recipe.cookTime}", style = MaterialTheme.typography.labelSmall)
                                    if (recipe.sourceUrl.isNotBlank()) Text("Imported", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPlanner) MealPlannerDialog(recipes, mealPlan, { showPlanner = false }) { next ->
        mealPlan = next
        persistPlan(next)
    }
    if (showShopping) ShoppingListDialog(recipes, mealPlan, { showShopping = false })
    if (showRecipeSearch) RecipeSearchDialog(
        onDismiss = { showRecipeSearch = false },
        onImport = { recipe ->
            update(listOf(recipe) + recipes.filterNot { it.sourceUrl == recipe.sourceUrl && recipe.sourceUrl.isNotBlank() })
            showRecipeSearch = false
            selected = recipe
        }
    )
    if (showForm) RecipeFormDialog(
        recipe = editing,
        onDismiss = { showForm = false; editing = null },
        onSave = { recipe ->
            val next = if (recipes.any { it.id == recipe.id }) recipes.map { if (it.id == recipe.id) recipe else it }
                else listOf(recipe) + recipes
            update(next)
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
            update(listOf(recipe) + recipes.filterNot { it.sourceUrl == recipe.sourceUrl && recipe.sourceUrl.isNotBlank() })
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
private fun RecipeFormDialog(recipe: Recipe?, onDismiss: () -> Unit, onSave: (Recipe) -> Unit) {
    var title by remember(recipe?.id) { mutableStateOf(recipe?.title ?: "") }
    var description by remember(recipe?.id) { mutableStateOf(recipe?.description ?: "") }
    var ingredients by remember(recipe?.id) { mutableStateOf(recipe?.ingredients ?: "") }
    var instructions by remember(recipe?.id) { mutableStateOf(recipe?.instructions ?: "") }
    var servings by remember(recipe?.id) { mutableStateOf(recipe?.servings ?: "2") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (recipe == null) "Add recipe" else "Edit recipe") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Recipe name *") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("Description") })
                OutlinedTextField(servings, { servings = it }, label = { Text("Servings") }, singleLine = true)
                OutlinedTextField(ingredients, { ingredients = it }, label = { Text("Ingredients (one per line)") }, minLines = 3)
                OutlinedTextField(instructions, { instructions = it }, label = { Text("Instructions") }, minLines = 4)
            }
        },
        confirmButton = {
            Button(enabled = title.isNotBlank(), onClick = {
                val base = recipe ?: Recipe(title = title.trim())
                onSave(base.copy(title = title.trim(), description = description.trim(), ingredients = ingredients.trim(),
                    instructions = instructions.trim(), servings = servings.trim()))
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
    var address by remember { mutableStateOf(initialAddress) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text("Import a recipe") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (sharedText.isNotBlank())
                    "Shared from another app. If a recipe link was included, PantryPal has filled it in below. Tap Import to try saving it."
                    else "Paste a recipe webpage URL. PantryPal looks for standard structured recipe data.")
                if (sharedText.isNotBlank() && address.isBlank()) {
                    Text("This share didn't include a web link. Open the original post, copy its recipe text, then choose Enter manually in PantryPal.",
                        style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(address, { address = it; error = null }, label = { Text("Recipe webpage URL") }, singleLine = true)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Some sites block imports or do not publish recipe data.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(enabled = !loading && address.isNotBlank(), onClick = {
                loading = true
                error = null
                scope.launch {
                    try { onImport(RecipeImporter().import(address)) }
                    catch (e: Exception) { error = e.message ?: "Import failed. Try manual entry." }
                    finally { loading = false }
                }
            }) { Text(if (loading) "Importing…" else "Import") }
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
private fun RecipeSearchDialog(onDismiss: () -> Unit, onImport: (Recipe) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RecipeSearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var importingUrl by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!searching && importingUrl == null) onDismiss() },
        title = { Text("Search recipes") },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Search the web for recipes from popular cooking websites, then import a result into PantryPal.")
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Dish or ingredient") },
                    placeholder = { Text("e.g. chicken curry") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = query.isNotBlank() && !searching && importingUrl == null,
                    onClick = {
                        searching = true
                        error = null
                        results = emptyList()
                        scope.launch {
                            try { results = RecipeWebSearch().search(query) }
                            catch (e: Exception) { error = e.message ?: "Search failed. Try again." }
                            finally { searching = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (searching) "Searching…" else "Search the web") }
                if (searching || importingUrl != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                results.forEach { result ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFFFFBF2))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(result.title, fontWeight = FontWeight.Bold)
                            Text(
                                Uri.parse(result.url).host.orEmpty().removePrefix("www."),
                                style = MaterialTheme.typography.labelSmall
                            )
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
                            ) { Text(if (importingUrl == result.url) "Importing…" else "Import recipe") }
                        }
                    }
                }
                Text(
                    "Importing works best on pages that publish recipe ingredients and instructions as structured data. Some sites may block imports.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(enabled = importingUrl == null && !searching, onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun RecipeDetailDialog(recipe: Recipe, onDismiss: () -> Unit, onImportAnother: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var cookingMode by remember(recipe.id) { mutableStateOf(false) }
    var servings by remember(recipe.id) { mutableIntStateOf(recipe.servings.toIntOrNull()?.coerceAtLeast(1) ?: 2) }
    val ingredients = remember(recipe.ingredients, servings, recipe.servings) {
        scaleIngredients(recipe.ingredients, servings, recipe.servings.toIntOrNull()?.coerceAtLeast(1) ?: 2)
    }
    val steps = remember(recipe.instructions) { recipe.instructions.lines().map { it.trim() }.filter { it.isNotBlank() } }
    val checkedSteps = remember(recipe.id, cookingMode) { mutableStateMapOf<Int, Boolean>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(recipe.title) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recipe.description.isNotBlank()) Text(recipe.description)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Servings", fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { servings = (servings - 1).coerceAtLeast(1) }) { Text("−") }
                    Text("$servings")
                    IconButton(onClick = { servings = (servings + 1).coerceAtMost(100) }) { Text("+") }
                }
                if (recipe.ingredients.isNotBlank()) {
                    Text("Ingredients", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(ingredients)
                }
                if (recipe.instructions.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Cooking mode", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Switch(checked = cookingMode, onCheckedChange = { cookingMode = it })
                    }
                    Text("Instructions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (cookingMode) steps.forEachIndexed { index, step ->
                        Row(verticalAlignment = Alignment.Top) {
                            Checkbox(checked = checkedSteps[index] == true, onCheckedChange = { checkedSteps[index] = it })
                            Text(step, modifier = Modifier.padding(top = 12.dp).weight(1f))
                        }
                    } else Text(recipe.instructions)
                }
                if (recipe.sourceUrl.isNotBlank()) Text("Source: ${recipe.sourceUrl}", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onEdit) { Text("Edit") } },
        dismissButton = {
            Row {
                TextButton(onClick = onImportAnother) { Text("Import another") }
                TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
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
