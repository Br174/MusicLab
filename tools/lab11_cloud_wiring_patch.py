from pathlib import Path

# Gemini shared configuration: allow a Worker endpoint even without a local Gemini key.
p = Path('app/src/main/kotlin/com/metrolist/music/ui/component/GeminiCoverVerification.kt')
s = p.read_text()
old = '''internal data class GeminiCoverVerificationConfig(
    val apiKey: String,
    val model: String,
)'''
new = '''internal data class GeminiCoverVerificationConfig(
    val apiKey: String,
    val model: String,
    val cloudEndpoint: String = "",
    val useCloudMemory: Boolean = true,
)'''
if old not in s:
    raise SystemExit('Config Gemini non trovata')
p.write_text(s.replace(old, new, 1))

# Cover screen: read cloud settings and pass them into the shared config.
p = Path('app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt')
s = p.read_text()
anchor = 'import com.metrolist.music.constants.MusicAiCoverEnabledKey\n'
if anchor not in s:
    raise SystemExit('Import Cover non trovato')
s = s.replace(anchor, anchor + 'import com.metrolist.music.constants.MusicAiCloudEndpointKey\nimport com.metrolist.music.constants.MusicAiCloudMemoryEnabledKey\n', 1)

anchor = '    val foreignAiEnabled by rememberPreference(MusicAiForeignEnabledKey, true)\n'
if anchor not in s:
    raise SystemExit('Preferenze Cover non trovate')
s = s.replace(anchor, anchor + '    val cloudMemoryEnabled by rememberPreference(MusicAiCloudMemoryEnabledKey, true)\n    val cloudEndpoint by rememberPreference(MusicAiCloudEndpointKey, "")\n', 1)

old = '''    val geminiConfig = effectiveKey.takeIf { it.isNotBlank() }?.let {
        GeminiCoverVerificationConfig(apiKey = it, model = effectiveModel)
    }'''
new = '''    val geminiConfig = if (effectiveKey.isNotBlank() || cloudEndpoint.isNotBlank()) {
        GeminiCoverVerificationConfig(
            apiKey = effectiveKey,
            model = effectiveModel,
            cloudEndpoint = cloudEndpoint,
            useCloudMemory = cloudMemoryEnabled,
        )
    } else {
        null
    }'''
if old not in s:
    raise SystemExit('Costruzione config Cover non trovata')
s = s.replace(old, new, 1)

anchor = '        foreignAiEnabled,\n'
if anchor not in s:
    raise SystemExit('LaunchedEffect Cover non trovato')
s = s.replace(anchor, anchor + '        cloudMemoryEnabled,\n        cloudEndpoint,\n', 1)
p.write_text(s)

# Originali screen: same cloud configuration, without changing its UI.
p = Path('app/src/main/kotlin/com/metrolist/music/ui/component/OriginalVersionScreen.kt')
s = p.read_text()
anchor = 'import com.metrolist.music.constants.MusicAiEngineEnabledKey\n'
if anchor not in s:
    raise SystemExit('Import Originali non trovato')
s = s.replace(anchor, anchor + 'import com.metrolist.music.constants.MusicAiCloudEndpointKey\nimport com.metrolist.music.constants.MusicAiCloudMemoryEnabledKey\n', 1)

anchor = '    val originalsAiEnabled by rememberPreference(MusicAiOriginalsEnabledKey, true)\n'
if anchor not in s:
    raise SystemExit('Preferenze Originali non trovate')
s = s.replace(anchor, anchor + '    val cloudMemoryEnabled by rememberPreference(MusicAiCloudMemoryEnabledKey, true)\n    val cloudEndpoint by rememberPreference(MusicAiCloudEndpointKey, "")\n', 1)

old = '''    val geminiConfig = effectiveKey.takeIf { it.isNotBlank() }?.let {
        GeminiCoverVerificationConfig(apiKey = it, model = effectiveModel)
    }'''
new = '''    val geminiConfig = if (effectiveKey.isNotBlank() || cloudEndpoint.isNotBlank()) {
        GeminiCoverVerificationConfig(
            apiKey = effectiveKey,
            model = effectiveModel,
            cloudEndpoint = cloudEndpoint,
            useCloudMemory = cloudMemoryEnabled,
        )
    } else {
        null
    }'''
if old not in s:
    raise SystemExit('Costruzione config Originali non trovata')
s = s.replace(old, new, 1)

anchor = '        effectiveModel,\n'
if anchor not in s:
    raise SystemExit('LaunchedEffect Originali non trovato')
s = s.replace(anchor, anchor + '        cloudMemoryEnabled,\n        cloudEndpoint,\n', 1)
p.write_text(s)

# Cover AI discovery: cloud cache first, direct Gemini fallback.
p = Path('app/src/main/kotlin/com/metrolist/music/ui/component/GeminiAiCoverDiscovery.kt')
s = p.read_text()
old = '''        if (config.apiKey.isBlank() || originalTitle.isBlank()) {
            return@withContext AiCoverDiscoveryResult(null, emptyList())
        }
        val cacheKey = "initial11|${config.model}|${canonical(originalTitle)}|${canonical(originalArtist)}"'''
new = '''        if (originalTitle.isBlank() || (config.apiKey.isBlank() && config.cloudEndpoint.isBlank())) {
            return@withContext AiCoverDiscoveryResult(null, emptyList())
        }
        val cacheKey = "initial11|${config.model}|${canonical(originalTitle)}|${canonical(originalArtist)}"'''
if old not in s:
    raise SystemExit('Guardia initial Cover non trovata')
s = s.replace(old, new, 1)

anchor = '''        initialCache[cacheKey]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let {
            return@withContext it.value
        }

        val prompt ='''
cloud = '''        initialCache[cacheKey]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let {
            return@withContext it.value
        }

        if (config.cloudEndpoint.isNotBlank()) {
            val cloudResult = CloudMusicDiscovery.discoverCover(
                title = originalTitle,
                artist = originalArtist,
                config = config,
                phase = "initial",
            )
            if (cloudResult != null) {
                val result = sanitize(cloudResult, originalArtist, AiCoverCategory.COVER, INITIAL_LIMIT)
                initialCache[cacheKey] = CachedDiscovery(result, System.currentTimeMillis() + CACHE_TTL_MS)
                return@withContext result
            }
        }
        if (config.apiKey.isBlank()) return@withContext AiCoverDiscoveryResult(null, emptyList())

        val prompt ='''
if anchor not in s:
    raise SystemExit('Cache initial Cover non trovata')
s = s.replace(anchor, cloud, 1)

old = '        if (config.apiKey.isBlank() || originalTitle.isBlank()) return@withContext\n'
new = '        if (originalTitle.isBlank() || (config.apiKey.isBlank() && config.cloudEndpoint.isBlank())) return@withContext\n'
if old not in s:
    raise SystemExit('Guardia expanded Cover non trovata')
s = s.replace(old, new, 1)

anchor = '''    ): List<AiCoverCandidate> {
        val excluded = existing.take(150).joinToString("\\n") {'''
insert = '''    ): List<AiCoverCandidate> {
        if (config.cloudEndpoint.isNotBlank()) {
            val cloudResult = runCatching {
                kotlinx.coroutines.runBlocking {
                    CloudMusicDiscovery.discoverCover(
                        title = originalTitle,
                        artist = originalArtist,
                        config = config,
                        phase = "expand",
                        existing = existing,
                        focus = focus.instructions,
                    )
                }
            }.getOrNull()
            if (cloudResult != null) {
                return sanitize(cloudResult, originalArtist, focus.category, focus.limit).versions
            }
        }
        if (config.apiKey.isBlank()) return emptyList()

        val excluded = existing.take(150).joinToString("\\n") {'''
if anchor not in s:
    raise SystemExit('Research round Cover non trovato')
s = s.replace(anchor, insert, 1)
p.write_text(s)

# Originali AI discovery: cloud memory/AI first, local Gemini as fallback.
p = Path('app/src/main/kotlin/com/metrolist/music/ui/component/GeminiOriginalDiscovery.kt')
s = p.read_text()
old = '''    ): GeminiOriginalIdentity? = withContext(Dispatchers.IO) {
        if (!config.isUsable()) return@withContext null
        if (currentTitle.isBlank()) return@withContext null

        val cacheKey ='''
new = '''    ): GeminiOriginalIdentity? = withContext(Dispatchers.IO) {
        if (currentTitle.isBlank()) return@withContext null

        val cacheKey ='''
if old not in s:
    raise SystemExit('Guardia Originali non trovata')
s = s.replace(old, new, 1)

anchor = '''        cache[cacheKey]
            ?.takeIf { it.expiresAtMs > System.currentTimeMillis() }
            ?.let { return@withContext it.identity }

        val prompt ='''
insert = '''        cache[cacheKey]
            ?.takeIf { it.expiresAtMs > System.currentTimeMillis() }
            ?.let { return@withContext it.identity }

        if (config.cloudEndpoint.isNotBlank()) {
            val cloudIdentity = CloudMusicDiscovery.identifyOriginal(
                title = currentTitle,
                artist = currentArtist,
                config = config,
            )
            if (cloudIdentity != null) {
                cache[cacheKey] = CachedIdentity(
                    identity = cloudIdentity,
                    expiresAtMs = System.currentTimeMillis() + CACHE_TTL_MS,
                )
                return@withContext cloudIdentity
            }
        }
        if (!config.isUsable()) return@withContext null

        val prompt ='''
if anchor not in s:
    raise SystemExit('Cache Originali non trovato')
s = s.replace(anchor, insert, 1)
p.write_text(s)
