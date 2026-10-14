package app.pantrypal

import android.content.Context
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
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = PantryOrange, onPrimary = PantryInk)) {
                PantryPalApp(store.load(), store::save,
                    onBackup = { createBackup.launch("PantryPal-backup.json") },
                    onRestore = { openBackup.launch(arrayOf("application/json", "text/json", "text/plain")) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PantryPalApp(initialRecipes: List<Recipe>, persist: (List<Recipe>) -> Unit, onBackup: () -> Unit, onRestore: () -> Unit) {
    var recipes by remember { mutableStateOf(initialRecipes) }
    var search by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Recipe?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
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
            onEdit = { editing = recipe; selected = null; showForm = true },
            onDelete = { update(recipes.filterNot { it.id == recipe.id }); selected = null }
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
private fun ImportDialog(onDismiss: () -> Unit, onManual: () -> Unit, onImport: (Recipe) -> Unit) {
    var address by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text("Import a recipe") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste a recipe webpage URL. PantryPal looks for standard structured recipe data.")
                OutlinedTextField(address, { address = it; error = null }, label = { Text("Recipe URL") }, singleLine = true)
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
private fun RecipeDetailDialog(recipe: Recipe, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(recipe.title) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recipe.description.isNotBlank()) Text(recipe.description)
                Text("Servings: ${recipe.servings}", fontWeight = FontWeight.SemiBold)
                if (recipe.ingredients.isNotBlank()) {
                    Text("Ingredients", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(recipe.ingredients)
                }
                if (recipe.instructions.isNotBlank()) {
                    Text("Instructions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(recipe.instructions)
                }
                if (recipe.sourceUrl.isNotBlank()) Text("Source: ${recipe.sourceUrl}", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onEdit) { Text("Edit") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}
