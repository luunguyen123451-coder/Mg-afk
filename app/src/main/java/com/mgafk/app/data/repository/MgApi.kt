package com.mgafk.app.data.repository

import com.mgafk.app.data.AppJson
import com.mgafk.app.data.AppLog
import com.mgafk.app.data.model.WeatherEvent
import com.mgafk.app.data.model.WeatherForecast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Client for https://mg-api.ariedam.fr
 *
 * Sprites: GET /assets/sprites/{category}/{name}.png
 */
object MgApi {

    private const val TAG = "MgApi"
    private const val BASE_URL = "https://mg-api.ariedam.fr"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = AppJson.default

    // ---- Thread-safe cache ----

    private val cache = ConcurrentHashMap<String, LinkedHashMap<String, GameEntry>>()
    private val mutationsCache = ConcurrentHashMap<String, MutationEntry>()
    private val plantSpriteMetaCache = ConcurrentHashMap<String, SpriteMetadata>()

    /** Rarity tiers in game order (lowest -> highest) */
    val RARITY_ORDER = listOf("Common", "Uncommon", "Rare", "Legendary", "Mythic", "Divine", "Celestial")

    data class GameEntry(
        val id: String,
        val name: String,
        val sprite: String?,
        val rarity: String? = null,
        val cropSprite: String? = null,
        val maxScale: Double? = null,
        val maxSizeMultiplier: Double? = null,
        val baseSellPrice: Double? = null,
        val hoursToMature: Double? = null,
        val maturitySellPrice: Double? = null,
        val color: String? = null,
        val diet: List<String> = emptyList(),
        val plantSprite: String? = null,
        val plantSlotOffsets: List<SlotOffset> = emptyList(),
        val plantSlotCapacity: Int? = null,
        val plantBaseTileScale: Double? = null,
        val plantTileTransformOrigin: String? = null,
        val cropBaseTileScale: Double? = null,
        val cropTransformOrigin: String? = null,
        val faunaSpawnWeights: Map<String, Double> = emptyMap(),
        val coinsToFullyReplenishHunger: Int? = null,
        val upgrades: List<DecorUpgrade> = emptyList(),
        val isOneTimePurchase: Boolean = false,
        val maxInventoryQuantity: Int? = null,
        val eligibleShops: List<String> = emptyList(),
    ) {
        val rarityIndex: Int get() = RARITY_ORDER.indexOf(rarity).let { if (it < 0) RARITY_ORDER.size else it }

        val plantMaxGrowSlots: Int
            get() = plantSlotCapacity ?: plantSlotOffsets.size.takeIf { it > 0 } ?: 1
    }

    data class SlotOffset(val x: Double, val y: Double, val rotation: Double)

    data class DecorUpgrade(
        val fromCapacitySlots: Int,
        val toCapacitySlots: Int,
        val dustCost: Long,
    )

    data class SpriteMetadata(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val anchorX: Double,
        val anchorY: Double,
    )

    data class MutationEntry(
        val name: String,
        val coinMultiplier: Double,
        val sprite: String? = null,
    )

    // ---- Public API ----

    @Volatile
    var isReady = false
        private set

    suspend fun fetchWeatherStation(): WeatherForecast? = withContext(Dispatchers.IO) {
        val dashboard = getJson("/weather-station")?.let { WeatherStationParser.parse(it) }
            ?: return@withContext null

        val now = System.currentTimeMillis()
        val missing = buildList {
            if (!dashboard.hasHydro(now)) add(WeatherEvent.HYDRO_IDS)
            if (!dashboard.hasLunar(now)) add(WeatherEvent.LUNAR_IDS)
        }
        if (missing.isEmpty()) return@withContext dashboard

        val extra = missing.flatMap { ids -> fetchNextWeather(ids) }
        dashboard.copy(upcoming = (dashboard.upcoming + extra).distinctBy { it.startsAtMs to it.id }
            .sortedBy { it.startsAtMs })
    }

    private fun fetchNextWeather(ids: List<String>): List<WeatherEvent> {
        val query = ids.joinToString(",")
        return getJson("/weather-station/next?ids=$query&count=2")
            ?.let { WeatherStationParser.parseEvents(it) }
            .orEmpty()
    }

    private fun getJson(path: String): JsonObject? = try {
        val request = Request.Builder()
            .url("$BASE_URL$path")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                AppLog.w(TAG, "HTTP ${response.code} for $path")
                null
            } else {
                response.body?.string()?.let { json.parseToJsonElement(it).jsonObject }
            }
        }
    } catch (e: Exception) {
        AppLog.w(TAG, "Request failed for $path: ${e.message}")
        null
    }

    suspend fun preloadAll() {
        val categories = listOf("pets", "items", "plants", "decors", "eggs", "weathers", "abilities")
        coroutineScope {
            val jobs = categories.map { cat ->
                async(Dispatchers.IO) {
                    try {
                        val data = fetchCategory(cat)
                        cache[cat] = data
                        AppLog.d(TAG, "Loaded $cat: ${data.size} entries")
                        if (cat == "decors") logStorageUpgradeCounts(data)
                    } catch (e: Exception) {
                        AppLog.e(TAG, "Failed to load $cat: ${e.message}")
                        try {
                            val data = fetchCategory(cat)
                            cache[cat] = data
                            AppLog.d(TAG, "Retry OK $cat: ${data.size} entries")
                            if (cat == "decors") logStorageUpgradeCounts(data)
                        } catch (e2: Exception) {
                            AppLog.e(TAG, "Retry also failed for $cat: ${e2.message}")
                        }
                    }
                }
            }
            val mutJob = async(Dispatchers.IO) {
                try {
                    val data = fetchMutations()
                    mutationsCache.putAll(data)
                    AppLog.d(TAG, "Loaded mutations: ${data.size} entries")
                } catch (e: Exception) {
                    AppLog.e(TAG, "Failed to load mutations: ${e.message}")
                    try {
                        val data = fetchMutations()
                        mutationsCache.putAll(data)
                        AppLog.d(TAG, "Retry OK mutations: ${data.size} entries")
                    } catch (e2: Exception) {
                        AppLog.e(TAG, "Retry also failed for mutations: ${e2.message}")
                    }
                }
            }
            val spriteMetaJob = async(Dispatchers.IO) {
                try {
                    val data = fetchPlantSpriteMetadata()
                    plantSpriteMetaCache.putAll(data)
                    AppLog.d(TAG, "Loaded plant sprite metadata: ${data.size} entries")
                } catch (e: Exception) {
                    AppLog.e(TAG, "Failed to load plant sprite metadata: ${e.message}")
                }
            }
            jobs.forEach { it.await() }
            mutJob.await()
            spriteMetaJob.await()
            isReady = true
            AppLog.d(TAG, "All preloaded. Cache keys: ${cache.keys}")
        }
    }

    private fun logStorageUpgradeCounts(decors: Map<String, GameEntry>) {
        for (id in listOf("PetHutch", "SeedSilo", "DecorShed", "ToolShack")) {
            val upgrades = decors[id]?.upgrades ?: emptyList()
            AppLog.d(TAG, "$id upgrades: ${upgrades.size} tiers ${upgrades.map { "${it.fromCapacitySlots}->${it.toCapacitySlots}" }}")
        }
    }

    fun getPets(): Map<String, GameEntry> = cache["pets"] ?: emptyMap()
    fun getItems(): Map<String, GameEntry> = cache["items"] ?: emptyMap()
    fun getPlants(): Map<String, GameEntry> = cache["plants"] ?: emptyMap()
    fun getDecors(): Map<String, GameEntry> = cache["decors"] ?: emptyMap()
    fun getEggs(): Map<String, GameEntry> = cache["eggs"] ?: emptyMap()
    fun getWeathers(): Map<String, GameEntry> = cache["weathers"] ?: emptyMap()
    fun getAbilities(): Map<String, GameEntry> = cache["abilities"] ?: emptyMap()
    fun getMutations(): Map<String, MutationEntry> = mutationsCache

    fun getPlantSpriteMetadata(spriteName: String): SpriteMetadata? =
        plantSpriteMetaCache[spriteName]

    fun spriteUrl(category: String, name: String): String =
        "$BASE_URL/assets/sprites/$category/$name.png"

    fun uiSpriteUrl(name: String): String = spriteUrl("ui", name)

    fun cropSizeMultiplier(species: String, size: Int): Double =
        CropSize.multiplier(size, getPlants()[species]?.maxSizeMultiplier ?: 1.0)

    fun plantSpriteUrl(name: String): String = spriteUrl("plants", name)

    val coinBagUrl: String get() = uiSpriteUrl("CoinBag")
    val lockSpriteUrl: String get() = uiSpriteUrl("Locked")
    val unlockSpriteUrl: String get() = uiSpriteUrl("Unlocked")
    val magicDustUrl: String get() = spriteUrl("items", "MagicDust")

    val preservationSpriteUrl: String get() = uiSpriteUrl("PreservationIcon")

    fun raritySpriteUrl(rarity: String): String = uiSpriteUrl("Rarity$rarity")

    private val MUTATION_SPRITE_ALIAS = mapOf("Ambershine" to "Amberlit")

    fun mutationSpriteUrl(mutation: String): String {
        val name = MUTATION_SPRITE_ALIAS[mutation] ?: mutation
        return uiSpriteUrl("Mutation$name")
    }

    fun composedSpriteUrl(key: String, mutations: List<String>): String? {
        if (mutations.isEmpty()) return null
        val encodedKey = URLEncoder.encode(key, "UTF-8")
        val encodedMuts = mutations.joinToString(",") { URLEncoder.encode(it, "UTF-8") }
        return "$BASE_URL/assets/sprites/composed?key=$encodedKey&mutations=$encodedMuts"
    }

    fun petSpriteUrl(species: String, mutations: List<String> = emptyList()): String? {
        val baseUrl = getPets()[species]?.sprite ?: return null
        if (mutations.isEmpty()) return baseUrl
        val spriteName = spriteNameFromUrl(baseUrl) ?: return baseUrl
        return composedSpriteUrl("sprite/pet/$spriteName", mutations) ?: baseUrl
    }

    fun cropSpriteUrl(species: String, mutations: List<String> = emptyList()): String? {
        val baseUrl = getPlants()[species]?.cropSprite ?: return null
        if (mutations.isEmpty()) return baseUrl
        val spriteName = spriteNameFromUrl(baseUrl) ?: return baseUrl
        return composedSpriteUrl("sprite/plant/$spriteName", mutations) ?: baseUrl
    }

    private fun spriteNameFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val file = url.substringAfterLast('/').substringBefore('?')
        return file.removeSuffix(".png").ifBlank { null }
    }

    fun findPet(speciesId: String): GameEntry? = getPets()[speciesId]

    fun findItem(itemId: String): GameEntry? {
        getPlants()[itemId]?.let { return it }
        getItems()[itemId]?.let { return it }
        getEggs()[itemId]?.let { return it }
        getDecors()[itemId]?.let { return it }
        for (getter in listOf(::getPlants, ::getItems, ::getEggs, ::getDecors)) {
            val match = getter().entries.find { it.key.equals(itemId, ignoreCase = true) }
            if (match != null) return match.value
        }
        return null
    }

    fun categoryOf(itemId: String): String? {
        val categories = listOf(
            "plants" to ::getPlants,
            "items" to ::getItems,
            "eggs" to ::getEggs,
            "decors" to ::getDecors,
        )
        for ((name, getter) in categories) {
            if (getter().containsKey(itemId)) return name
        }
        for ((name, getter) in categories) {
            if (getter().keys.any { it.equals(itemId, ignoreCase = true) }) return name
        }
        return null
    }

    fun itemDisplayName(itemId: String): String = findItem(itemId)?.name ?: itemId

    fun abilityDisplayName(abilityId: String): String =
        getAbilities()[abilityId]?.name ?: abilityId

    fun weatherInfo(weatherKey: String): GameEntry? {
        getWeathers()[weatherKey]?.let { return it }
        return getWeathers().entries.find { it.key.equals(weatherKey, ignoreCase = true) }?.value
    }

    fun clearCache() {
        cache.clear()
        mutationsCache.clear()
        plantSpriteMetaCache.clear()
        isReady = false
    }

    // ---- Internal Helper Functions ----

    private fun fetchCategory(category: String): LinkedHashMap<String, GameEntry> {
        val result = LinkedHashMap<String, GameEntry>()
        val root = getJson("/data/$category") ?: return result
        val dataObj = root["data"] as? JsonObject ?: root
        for ((id, element) in dataObj) {
            val obj = element as? JsonObject ?: continue
            val entry = GameEntry(
                id = id,
                name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: id,
                sprite = (obj["sprite"] as? JsonPrimitive)?.contentOrNull,
                rarity = (obj["rarity"] as? JsonPrimitive)?.contentOrNull,
                cropSprite = (obj["cropSprite"] as? JsonPrimitive)?.contentOrNull,
                maxScale = (obj["maxScale"] as? JsonPrimitive)?.doubleOrNull,
                maxSizeMultiplier = (obj["maxSizeMultiplier"] as? JsonPrimitive)?.doubleOrNull,
                baseSellPrice = (obj["baseSellPrice"] as? JsonPrimitive)?.doubleOrNull,
                hoursToMature = (obj["hoursToMature"] as? JsonPrimitive)?.doubleOrNull,
                maturitySellPrice = (obj["maturitySellPrice"] as? JsonPrimitive)?.doubleOrNull,
                color = (obj["color"] as? JsonPrimitive)?.contentOrNull,
            )
            result[id] = entry
        }
        return result
    }

    private fun fetchMutations(): Map<String, MutationEntry> {
        val result = mutableMapOf<String, MutationEntry>()
        val root = getJson("/data/mutations") ?: return result
        val dataObj = root["data"] as? JsonObject ?: root
        for ((id, element) in dataObj) {
            val obj = element as? JsonObject ?: continue
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: id
            val multiplier = (obj["coinMultiplier"] as? JsonPrimitive)?.doubleOrNull ?: 1.0
            val sprite = (obj["sprite"] as? JsonPrimitive)?.contentOrNull
            result[id] = MutationEntry(name, multiplier, sprite)
        }
        return result
    }

    private fun fetchPlantSpriteMetadata(): Map<String, SpriteMetadata> {
        val request = Request.Builder()
            .url("$BASE_URL/assets/sprite-data?cat=plants&full=1")
            .header("Accept", "application/json")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code} for sprite-data?cat=plants")
        }
        val body = response.body?.string()
            ?: throw Exception("Empty body for sprite-data?cat=plants")
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw Exception("Invalid JSON for sprite-data?cat=plants")

        val categories = root["categories"] as? JsonArray ?: return emptyMap()
        val result = mutableMapOf<String, SpriteMetadata>()
        for (catElement in categories) {
            val catObj = catElement as? JsonObject ?: continue
            val items = catObj["items"] as? JsonArray ?: continue
            for (itemElement in items) {
                val itemObj = itemElement as? JsonObject ?: continue
                val name = (itemObj["name"] as? JsonPrimitive)?.contentOrNull ?: continue
                val sourceSize = itemObj["sourceSize"] as? JsonObject
                val anchor = itemObj["anchor"] as? JsonObject
                val w = (sourceSize?.get("w") as? JsonPrimitive)?.intOrNull ?: 0
                val h = (sourceSize?.get("h") as? JsonPrimitive)?.intOrNull ?: 0
                val ax = (anchor?.get("x") as? JsonPrimitive)?.doubleOrNull ?: 0.5
                val ay = (anchor?.get("y") as? JsonPrimitive)?.doubleOrNull ?: 0.5
                result[name] = SpriteMetadata(w, h, ax, ay)
            }
        }
        return result
    }
}

// ---- Dummy WeatherStationParser & CropSize to avoid missing references ----

private object WeatherStationParser {
    fun parse(json: JsonObject): WeatherForecast = WeatherForecast()
    fun parseEvents(json: JsonObject): List<WeatherEvent> = emptyList()
}

private object CropSize {
    fun multiplier(size: Int, maxMult: Double): Double = 1.0
}
