package app.pantrypal

/**
 * 200 original, UK-inspired slow-cooker ideas bundled for offline use.
 * Variations are intentionally simple and use everyday supermarket ingredients.
 */
internal object SlowCookerRecipes {
    private data class Base(val name: String, val kind: String, val ingredients: String)
    private data class Twist(val name: String, val seasoning: String)

    private val bases = listOf(
        Base("Beef stew", "beef", "500g stewing beef, 3 carrots, 2 potatoes, 1 onion, 400ml beef stock"),
        Base("Cottage pie filling", "beef mince", "500g beef mince, 1 onion, 2 carrots, 200g peas, 300ml beef stock"),
        Base("Sausage casserole", "sausages", "8 pork sausages, 2 carrots, 1 onion, 400g chopped tomatoes, 300ml stock"),
        Base("Chicken casserole", "chicken", "600g chicken thighs, 3 carrots, 2 celery sticks, 1 onion, 400ml chicken stock"),
        Base("Pulled pork", "pork shoulder", "800g pork shoulder, 1 onion, 150ml apple juice, 2 tbsp tomato purée"),
        Base("Lamb hotpot", "lamb", "600g diced lamb, 2 onions, 3 carrots, 500g potatoes, 350ml lamb stock"),
        Base("Chicken tikka curry", "chicken", "600g chicken thighs, 1 onion, 400g chopped tomatoes, 200ml coconut milk"),
        Base("Beef chilli", "beef mince", "500g beef mince, 1 onion, 2 peppers, 400g kidney beans, 400g chopped tomatoes"),
        Base("Chicken soup", "chicken", "500g chicken, 2 carrots, 2 celery sticks, 1 onion, 1.2 litres chicken stock"),
        Base("Lentil soup", "vegetarian", "250g red lentils, 2 carrots, 1 onion, 400g chopped tomatoes, 1 litre vegetable stock"),
        Base("Mince and dumplings", "beef mince", "500g beef mince, 1 onion, 2 carrots, 400ml beef stock, 100g self-raising flour"),
        Base("Pork and apple casserole", "pork", "600g diced pork, 2 apples, 1 onion, 300ml chicken stock"),
        Base("Turkey meatballs", "turkey", "500g turkey mince, 1 egg, 50g breadcrumbs, 1 onion, 400g passata"),
        Base("Mushroom stroganoff", "vegetarian", "500g mushrooms, 1 onion, 300ml vegetable stock, 150g crème fraîche"),
        Base("Three-bean stew", "vegetarian", "2 tins mixed beans, 1 onion, 2 carrots, 400g chopped tomatoes, 300ml vegetable stock"),
        Base("Gammon and potatoes", "gammon", "700g gammon joint, 600g potatoes, 2 carrots, 1 onion, 400ml stock"),
        Base("Chicken and leek pie filling", "chicken", "600g chicken thighs, 2 leeks, 2 carrots, 300ml chicken stock, 100ml cream"),
        Base("BBQ pulled chicken", "chicken", "600g chicken thighs, 1 onion, 200ml BBQ sauce, 100ml chicken stock"),
        Base("Vegetable curry", "vegetarian", "2 potatoes, 2 carrots, 1 cauliflower, 1 onion, 400g chopped tomatoes"),
        Base("Rice pudding", "dessert", "150g pudding rice, 1 litre whole milk, 50g sugar, 1 tsp vanilla")
    )
    private val twists = listOf(
        Twist("classic", "Season with salt, black pepper and 1 tsp dried thyme."),
        Twist("herby", "Add 1 tsp mixed herbs and 1 bay leaf."),
        Twist("garlic", "Add 2 crushed garlic cloves and 1 tsp paprika."),
        Twist("mustard", "Stir in 1 tbsp English mustard and 1 tsp thyme."),
        Twist("smoky", "Add 1 tsp smoked paprika and a pinch of chilli flakes."),
        Twist("tomato", "Add 2 tbsp tomato purée and 1 tsp oregano."),
        Twist("Worcestershire", "Add 1 tbsp Worcestershire sauce and plenty of black pepper."),
        Twist("mild curry", "Add 2 tsp mild curry powder and 1 tsp ground cumin."),
        Twist("sweet chilli", "Add 1 tbsp sweet chilli sauce and 1 tsp garlic granules."),
        Twist("rosemary", "Add 1 tsp rosemary and 1 tsp onion granules.")
    )

    fun all(): List<Recipe> = bases.flatMapIndexed { baseIndex, base ->
        twists.mapIndexed { twistIndex, twist ->
            val title = "Slow cooker ${base.name.lowercase().replaceFirstChar { it.uppercase() }} - ${twist.name.replaceFirstChar { it.uppercase() }}"
            val extra = if (base.kind == "dessert") "Add the seasoning only if it suits the pudding; for this rice pudding use the vanilla and omit savoury seasonings." else twist.seasoning
            val ingredients = if (base.kind == "dessert") {
                "${base.ingredients}\nOptional: pinch of cinnamon"
            } else "${base.ingredients}\n${extra}"
            val instructions = when (base.kind) {
                "dessert" -> "Rinse the rice. Put all ingredients in the slow cooker and stir well. Cook on LOW for 2–3 hours, stirring once or twice if practical, until creamy and the rice is tender. Add a splash of milk if it thickens too much. Follow your slow cooker manufacturer's guidance."
                "vegetarian" -> "Chop the vegetables evenly and rinse the beans or lentils if using. Add everything except any cream or delicate garnish to the slow cooker and stir. Cook on LOW for 6–7 hours or HIGH for 3–4 hours, until vegetables are tender. Adjust seasoning and add cream or fresh herbs at the end if wanted. Check that the food is piping hot before serving."
                else -> "Prepare and chop the ingredients, then add them to the slow cooker with the stock or sauce and seasoning. Stir to combine. Cook on LOW for 6–8 hours or HIGH for 3–4 hours, until the meat is thoroughly cooked and tender. For mince, break up and brown it first if preferred. Thicken the sauce at the end if needed. Ensure poultry is fully cooked and food is piping hot before serving."
            }
            Recipe(
                id = "slowcooker-${baseIndex}-${twistIndex}",
                title = title,
                description = "An easy UK-style slow-cooker recipe using everyday ingredients. Best served with potatoes, rice, bread or seasonal vegetables as appropriate.",
                ingredients = ingredients,
                instructions = instructions,
                prepTime = "15 minutes",
                cookTime = if (base.kind == "dessert") "2–3 hours" else "6–8 hours on LOW",
                servings = "4"
            )
        }
    }
}