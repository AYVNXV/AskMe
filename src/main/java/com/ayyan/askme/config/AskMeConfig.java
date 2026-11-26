package com.ayyan.askme.config;

import com.ayyan.askme.AskMe;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AskMeConfig {

    public static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";
    public static final String DEFAULT_LANGUAGE = "Auto";

    public static final List<String> ALLOWED_MODELS = List.of(
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash",
            "gemini-3.6-flash",
            "gemini-3.7-flash");

    public static final List<String> SUPPORTED_LANGUAGES = List.of(
            "Roman Urdu",
            "Hinglish",
            "English",
            "Urdu",
            "Spanish",
            "Portuguese",
            "Arabic",
            "Turkish",
            "French",
            "German",
            "Indonesian",
            "Hindi",
            "Japanese",
            "Korean",
            "Russian",
            "Auto");

    /** Game sounds offered in the style screen. Any other sound id also works with /askme sound <id>. */
    public static final List<String> SOUND_PRESETS = List.of(
            "block.note_block.pling", "block.note_block.bell", "block.note_block.chime",
            "entity.experience_orb.pickup", "entity.player.levelup", "ui.toast.challenge_complete",
            "block.amethyst_block.chime", "block.bell.use", "entity.arrow.hit_player",
            "entity.villager.yes", "block.anvil.land", "entity.ender_dragon.growl", "off");

    public static final List<String> COLOR_PRESETS = List.of(
            "#FFD54F", "#FF5555", "#55FF55", "#55FFFF", "#FF55FF", "#FFAA00", "#FFFFFF", "#5555FF");

    public static final int MAX_HISTORY_TURNS = 12;
    public static final int MAX_CACHE_ENTRIES = 80;

    public String apiKey = "";
    public String commandKeyword = "ask";
    public String promptPrefix = "";
    public String nickname = "";
    public String language = DEFAULT_LANGUAGE;

    public boolean allowActions = true;
    public boolean allowClientControls = true;
    public boolean autoScanOnJoin = true;
    public boolean enableCache = true;

    public String geminiModel = DEFAULT_MODEL;
    public String geminiEndpoint = endpointFor(DEFAULT_MODEL);

    public boolean autoReply = false;
    public boolean savageMode = true;
    public boolean publicReply = true;
    public int replyCooldownSeconds = 15;
    public long cacheTtlSeconds = 900;

    /** Local protection for the free tier: max Gemini requests per minute. */
    public int maxRequestsPerMinute = 8;

    /** Tag reply: only answer when the other player really asks you something. */
    public boolean replyOnlyQuestions = true;

    /** Name highlight in chat (works for chat, kills, deaths, system text). */
    public boolean nameHighlight = true;
    public String highlightColor = "#FFD54F";
    public boolean highlightBold = true;
    public boolean highlightItalic = false;
    public boolean highlightUnderline = false;

    /** Game sound id played when your name appears. "off" = no sound. */
    public String nameSound = "block.note_block.pling";
    public int nameSoundVolume = 100;

    /** Background check of the game log for mod errors. */
    public boolean modWatch = true;

    /** false = the picture bundled in the mod, true = config/askme-bg.png. Only used in Ask Me settings screens. */
    public boolean useCustomBackground = false;
    /** How dark the settings background is, 0-90 percent. */
    public int backgroundDim = 45;

    /** Explain by itself (without a question) why you died or why a mod problem happened. */
    public boolean autoExplain = true;

    /** Saved places: key "server|name" -> {x, y, z}. */
    public Map<String, int[]> waypoints = new LinkedHashMap<>();
    /** Friends (lower-case names) whose join / leave is announced. */
    public List<String> friends = new ArrayList<>();
    public boolean friendAlert = true;
    /** Warning and sound when your health is 3 hearts or less. */
    public boolean lowHealthAlert = true;

    public List<ConversationTurn> conversationHistory = new ArrayList<>();
    public Map<String, CachedResponse> responseCache = new LinkedHashMap<>();
    public Map<String, ServerCommandCache> serverCaches = new LinkedHashMap<>();

    public static class ConversationTurn {
        public String role;
        public String text;

        public ConversationTurn() {
        }

        public ConversationTurn(String role, String text) {
            this.role = role;
            this.text = text;
        }
    }

    public static class CachedResponse {
        public String answer;
        public long createdAtMs;

        public CachedResponse() {
        }

        public CachedResponse(String answer, long createdAtMs) {
            this.answer = answer;
            this.createdAtMs = createdAtMs;
        }
    }

    public static class ServerCommandCache {
        public List<String> commands = new ArrayList<>();
        public List<String> helpLines = new ArrayList<>();
        public long scannedAtMs;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AskMeConfig instance;

    private AskMeConfig() {
    }

    public static synchronized AskMeConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static synchronized void load() {
        Path path = configPath();
        boolean createDefaultFile = !Files.exists(path);
        try {
            if (Files.exists(path)) {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                instance = GSON.fromJson(json, AskMeConfig.class);
                if (instance == null) {
                    instance = new AskMeConfig();
                }
            } else {
                instance = new AskMeConfig();
            }
        } catch (Exception e) {
            AskMe.LOGGER.error("[Ask Me] Failed to read config file; using defaults.", e);
            instance = new AskMeConfig();
        }

        if (instance.commandKeyword == null || instance.commandKeyword.isBlank()) {
            instance.commandKeyword = "ask";
        }
        if (instance.nickname == null) {
            instance.nickname = "";
        }
        if (instance.promptPrefix == null) {
            instance.promptPrefix = "";
        }
        if (instance.language == null || instance.language.isBlank()) {
            instance.language = DEFAULT_LANGUAGE;
        }
        if (instance.highlightColor == null || !instance.highlightColor.matches("#[0-9A-Fa-f]{6}")) {
            instance.highlightColor = "#FFD54F";
        }
        if (instance.nameSound == null || instance.nameSound.isBlank()) {
            instance.nameSound = "block.note_block.pling";
        }
        if (instance.nameSoundVolume < 0 || instance.nameSoundVolume > 100) {
            instance.nameSoundVolume = 100;
        }
        if (instance.backgroundDim < 0 || instance.backgroundDim > 90) {
            instance.backgroundDim = 45;
        }
        if (instance.maxRequestsPerMinute < 1 || instance.maxRequestsPerMinute > 60) {
            instance.maxRequestsPerMinute = 8;
        }
        if (instance.apiKey == null) {
            instance.apiKey = "";
        }
        instance.geminiModel = normalizeModel(instance.geminiModel);
        if (instance.geminiModel.isBlank()) {
            instance.geminiModel = DEFAULT_MODEL;
        }
        if (instance.replyCooldownSeconds < 3 || instance.replyCooldownSeconds > 600) {
            instance.replyCooldownSeconds = 15;
        }
        if (instance.cacheTtlSeconds < 10 || instance.cacheTtlSeconds > 86400) {
            instance.cacheTtlSeconds = 900;
        }

        String expectedEndpoint = endpointFor(instance.geminiModel);
        boolean migrated = !expectedEndpoint.equals(instance.geminiEndpoint);
        instance.geminiEndpoint = expectedEndpoint;

        if (instance.conversationHistory == null) {
            instance.conversationHistory = new ArrayList<>();
        }
        trimHistory();

        if (instance.responseCache == null) {
            instance.responseCache = new LinkedHashMap<>();
        }
        if (instance.serverCaches == null) {
            instance.serverCaches = new LinkedHashMap<>();
        }

        if (instance.waypoints == null) {
            instance.waypoints = new LinkedHashMap<>();
        }
        if (instance.friends == null) {
            instance.friends = new ArrayList<>();
        }

        if (createDefaultFile || migrated) {
            instance.save();
        }
    }

    private static void trimHistory() {
        if (instance.conversationHistory.size() > MAX_HISTORY_TURNS) {
            instance.conversationHistory = new ArrayList<>(instance.conversationHistory.subList(
                    instance.conversationHistory.size() - MAX_HISTORY_TURNS, instance.conversationHistory.size()));
        }
    }

    public synchronized void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AskMe.LOGGER.error("[Ask Me] Failed to save config file!", e);
        }
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public synchronized List<ConversationTurn> historySnapshot() {
        return new ArrayList<>(conversationHistory);
    }

    public synchronized void addConversation(String question, String answer) {
        conversationHistory.add(new ConversationTurn("user", trimText(question, 280)));
        conversationHistory.add(new ConversationTurn("model", trimText(answer, 550)));
        trimHistory();
        save();
    }

    /** Changing the language also forgets old chat turns and cached answers, so the old language is not copied. */
    public synchronized void setLanguage(String value) {
        String clean = value == null ? "" : value.trim();
        language = clean.isEmpty() ? DEFAULT_LANGUAGE : clean;
        conversationHistory.clear();
        responseCache.clear();
        save();
    }

    public synchronized void clearConversationHistory() {
        conversationHistory.clear();
        save();
    }

    public synchronized String getCachedResponse(String key) {
        if (!enableCache || key == null || responseCache == null) {
            return null;
        }
        CachedResponse entry = responseCache.get(key);
        if (entry == null) {
            return null;
        }
        long ageSec = (System.currentTimeMillis() - entry.createdAtMs) / 1000L;
        if (ageSec > cacheTtlSeconds) {
            responseCache.remove(key);
            return null;
        }
        return entry.answer;
    }

    public synchronized void putCachedResponse(String key, String answer) {
        if (!enableCache || key == null || answer == null || answer.isBlank()) {
            return;
        }
        if (responseCache.size() >= MAX_CACHE_ENTRIES) {
            String firstKey = responseCache.keySet().iterator().next();
            responseCache.remove(firstKey);
        }
        responseCache.put(key, new CachedResponse(answer, System.currentTimeMillis()));
    }

    public synchronized void saveServerScan(String serverId, List<String> commands, List<String> helpLines) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        ServerCommandCache cache = new ServerCommandCache();
        cache.commands = commands == null ? new ArrayList<>() : new ArrayList<>(commands);
        cache.helpLines = helpLines == null ? new ArrayList<>() : new ArrayList<>(helpLines);
        cache.scannedAtMs = System.currentTimeMillis();
        serverCaches.put(serverId, cache);
        if (serverCaches.size() > 30) {
            String oldest = serverCaches.keySet().iterator().next();
            serverCaches.remove(oldest);
        }
        save();
    }

    public synchronized ServerCommandCache getServerScan(String serverId) {
        if (serverId == null || serverCaches == null) {
            return null;
        }
        return serverCaches.get(serverId);
    }

    private static String trimText(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    public synchronized void reset() {
        apiKey = "";
        commandKeyword = "ask";
        promptPrefix = "";
        nickname = "";
        language = DEFAULT_LANGUAGE;
        allowActions = true;
        allowClientControls = true;
        autoScanOnJoin = true;
        enableCache = true;
        autoReply = false;
        savageMode = true;
        publicReply = true;
        replyCooldownSeconds = 15;
        cacheTtlSeconds = 900;
        maxRequestsPerMinute = 8;
        replyOnlyQuestions = true;
        nameHighlight = true;
        highlightColor = "#FFD54F";
        highlightBold = true;
        highlightItalic = false;
        highlightUnderline = false;
        nameSound = "block.note_block.pling";
        nameSoundVolume = 100;
        modWatch = true;
        autoExplain = true;
        useCustomBackground = false;
        backgroundDim = 45;
        geminiModel = DEFAULT_MODEL;
        geminiEndpoint = endpointFor(geminiModel);
        conversationHistory.clear();
        responseCache.clear();
        save();
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("askme.json");
    }

    public static String endpointFor(String model) {
        String normalized = normalizeModel(model);
        if (normalized.isBlank()) {
            normalized = DEFAULT_MODEL;
        }
        return "https://generativelanguage.googleapis.com/v1beta/models/" + normalized + ":generateContent";
    }

    public static String normalizeModel(String model) {
        String value = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("models/")) {
            value = value.substring("models/".length());
        }
        if (value.endsWith(":generatecontent")) {
            value = value.substring(0, value.length() - ":generatecontent".length());
        }
        if (value.matches("3\\.[567](-flash)?")) {
            value = "gemini-" + value + (value.endsWith("-flash") ? "" : "-flash");
        }
        return ALLOWED_MODELS.contains(value) ? value : "";
    }
}