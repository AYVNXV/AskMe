package com.ayyan.askme.gemini;

import com.ayyan.askme.AskMe;
import com.ayyan.askme.config.AskMeConfig;
import com.ayyan.askme.control.ClientControlService;
import com.ayyan.askme.limit.RequestGuard;
import com.ayyan.askme.scanner.ServerCommandScanner;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class GeminiService {

    public record Reply(boolean ok, String text, String errorMessage) {
        public static Reply success(String text) {
            return new Reply(true, text == null ? "" : text, "");
        }

        public static Reply failure(String message) {
            return new Reply(false, "", message);
        }
    }

    private static final Gson GSON = new Gson();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "AskMe-Gemini");
        thread.setDaemon(true);
        return thread;
    });

    private GeminiService() {
    }

    private static final int HISTORY_TURNS_SENT = 6;

    /** Short description so the model knows the exact style of each language. */
    private static String describeLanguage(String lang) {
        String l = lang.toLowerCase(Locale.ROOT);
        return switch (l) {
            case "roman urdu" -> "Roman Urdu (Urdu written with English letters, for example: \"aap kaise hain, main theek hoon\"). "
                    + "Do NOT use Urdu script and do NOT use plain English sentences.";
            case "hinglish" -> "Hinglish (Hindi mixed with English, written with English letters, for example: "
                    + "\"bhai ye kaam ho gaya, ab next try karo\").";
            case "urdu" -> "Urdu in Urdu script.";
            case "hindi" -> "Hindi in Devanagari script.";
            default -> lang;
        };
    }

    public static String buildLanguageRule(String selectedLanguage) {
        String lang = (selectedLanguage == null || selectedLanguage.isBlank())
                ? AskMeConfig.DEFAULT_LANGUAGE : selectedLanguage.trim();
        if (lang.equalsIgnoreCase("Auto")) {
            return "LANGUAGE RULE (HIGHEST PRIORITY): Reply in the exact language and script of the user's latest "
                    + "message. If the user writes Roman Urdu or Hinglish (English letters), reply the same way. "
                    + "Keep Minecraft item names, mobs, and commands unchanged.";
        }
        return "LANGUAGE RULE (HIGHEST PRIORITY): Write your ENTIRE visible reply in " + describeLanguage(lang)
                + " Ignore the language of earlier messages and of the user's question. "
                + "Keep Minecraft item names, mobs, and commands unchanged.";
    }

    /** Short reminder added to the user's own message, because small models forget the system rule. */
    public static String languageReminder(String selectedLanguage) {
        if (selectedLanguage == null || selectedLanguage.isBlank() || selectedLanguage.equalsIgnoreCase("Auto")) {
            return "";
        }
        return "\n\n(Answer in " + selectedLanguage.trim() + " only.)";
    }

    public static void ask(String question, Consumer<Reply> callback) {
        Minecraft client = Minecraft.getInstance();
        AskMeConfig config = AskMeConfig.get();
        final boolean actionsAllowed = config.allowActions;
        final boolean controlsAllowed = config.allowClientControls;
        final String nickname = config.nickname == null ? "" : config.nickname.trim();
        final String promptPrefix = config.promptPrefix == null ? "" : config.promptPrefix.trim();
        final String langRule = buildLanguageRule(config.language);
        final String serverCtx = ServerCommandScanner.buildContextSummary(client);

        String systemPrompt = langRule
                + " You are \"Ask Me\", a helpful assistant inside Minecraft. Respond concisely in plain text without markdown."
                + (actionsAllowed
                ? " To run a command, emit a standalone line: ACTION::/command"
                : "")
                + (controlsAllowed
                ? " To change client settings or game state, emit a standalone line: CONTROL::<op> <args> "
                + ClientControlService.protocolHelp()
                : "")
                + (nickname.isBlank() ? "" : " Player nickname: " + nickname + ".")
                + serverCtx
                + (promptPrefix.isBlank() ? "" : "\nAdditional instructions:\n" + promptPrefix);

        String cacheKey = config.geminiModel + "|" + config.language + "|" + question.trim().toLowerCase(Locale.ROOT);
        String cached = config.getCachedResponse(cacheKey);
        if (cached != null) {
            client.execute(() -> deliver(callback, Reply.success(cached)));
            return;
        }

        List<AskMeConfig.ConversationTurn> all = config.historySnapshot();
        final List<AskMeConfig.ConversationTurn> history = all.size() > HISTORY_TURNS_SENT
                ? all.subList(all.size() - HISTORY_TURNS_SENT, all.size()) : all;
        run(systemPrompt, history, question + languageReminder(config.language), cacheKey, 1024, 0.65d, true, callback);
    }

    /** Runs a reply callback on the game thread. An error inside a callback must never crash the game. */
    private static void deliver(Consumer<Reply> callback, Reply reply) {
        try {
            callback.accept(reply);
        } catch (Throwable problem) {
            AskMe.LOGGER.warn("[Ask Me] Reply handler failed: {}", problem.toString());
        }
    }

    public static void askOneShot(String systemPrompt, String userText, Consumer<Reply> callback) {
        run(systemPrompt, List.of(), userText, null, 400, 0.85d, false, callback);
    }

    private static void run(String systemPrompt, List<AskMeConfig.ConversationTurn> history, String userText,
                            String cacheKey, int maxTokens, double temperature, boolean saveHistory,
                            Consumer<Reply> callback) {
        AskMeConfig config = AskMeConfig.get();
        final String apiKey = config.apiKey == null ? "" : config.apiKey.trim();
        final String endpoint = AskMeConfig.endpointFor(config.geminiModel);

        int wait = RequestGuard.secondsUntilAllowed();
        if (wait > 0 && !apiKey.isBlank()) {
            final Reply limited = Reply.failure("Limit protection: wait " + wait
                    + " s and ask again. This keeps your free quota safe.");
            Minecraft.getInstance().execute(() -> deliver(callback, limited));
            return;
        }

        WORKERS.execute(() -> {
            Reply reply;
            try {
                reply = performRequest(apiKey, endpoint,
                        buildRequestBody(systemPrompt, history, userText, maxTokens, temperature));
                if (reply.ok()) {
                    if (saveHistory) {
                        config.addConversation(userText, reply.text());
                    }
                    if (cacheKey != null) {
                        config.putCachedResponse(cacheKey, reply.text());
                    }
                }
            } catch (Throwable unexpected) {
                AskMe.LOGGER.error("[Ask Me] Unexpected error while calling Gemini", unexpected);
                reply = Reply.failure("Unexpected internal error: " + unexpected.getClass().getSimpleName());
            }
            final Reply settled = reply;
            Minecraft.getInstance().execute(() -> deliver(callback, settled));
        });
    }

    private static Reply performRequest(String apiKey, String endpoint, String body) {
        if (apiKey.isBlank()) {
            return Reply.failure("No API key configured. Use /askme apikey <key> or open Settings.");
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(25))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException malformed) {
            return Reply.failure("Failed to construct Gemini request.");
        }

        HttpResponse<String> response;
        try {
            RequestGuard.recordSent();
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException timeout) {
            return Reply.failure("Gemini request timed out after 25 seconds.");
        } catch (IOException connectionProblem) {
            return Reply.failure("Unable to connect to Gemini API. Check network connection.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Reply.failure("Gemini request was interrupted.");
        }

        int status = response.statusCode();
        if (status >= 400) {
            return Reply.failure(mapStatusError(status, response.body()));
        }
        return parseSuccessBody(response.body());
    }

    private static String buildRequestBody(String systemPrompt, List<AskMeConfig.ConversationTurn> history,
                                           String userText, int maxTokens, double temperature) {
        JsonObject root = new JsonObject();

        JsonArray contents = new JsonArray();
        for (AskMeConfig.ConversationTurn turn : history) {
            if (turn == null || turn.text == null || turn.text.isBlank()) {
                continue;
            }
            contents.add(content("model".equals(turn.role) ? "model" : "user", turn.text));
        }
        contents.add(content("user", userText));
        root.add("contents", contents);

        JsonObject systemInstruction = new JsonObject();
        JsonArray systemParts = new JsonArray();
        systemParts.add(part(systemPrompt));
        systemInstruction.add("parts", systemParts);
        root.add("system_instruction", systemInstruction);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("temperature", temperature);
        generationConfig.addProperty("maxOutputTokens", maxTokens);
        root.add("generationConfig", generationConfig);

        return GSON.toJson(root);
    }

    private static JsonObject content(String role, String text) {
        JsonObject content = new JsonObject();
        content.addProperty("role", role);
        JsonArray parts = new JsonArray();
        parts.add(part(text));
        content.add("parts", parts);
        return content;
    }

    private static JsonObject part(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("text", text == null ? "" : text);
        return part;
    }

    private static Reply parseSuccessBody(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("promptFeedback") && json.get("promptFeedback").isJsonObject()) {
                JsonObject feedback = json.getAsJsonObject("promptFeedback");
                if (feedback.has("blockReason")) {
                    return Reply.failure("Prompt blocked: " + feedback.get("blockReason").getAsString());
                }
            }

            JsonArray candidates = json.has("candidates") && json.get("candidates").isJsonArray()
                    ? json.getAsJsonArray("candidates") : null;
            if (candidates == null || candidates.isEmpty()) {
                return Reply.failure("Gemini returned no candidates.");
            }

            JsonObject candidate = candidates.get(0).getAsJsonObject();
            StringBuilder answer = new StringBuilder();
            if (candidate.has("content") && candidate.get("content").isJsonObject()) {
                JsonObject content = candidate.getAsJsonObject("content");
                if (content.has("parts") && content.get("parts").isJsonArray()) {
                    for (var element : content.getAsJsonArray("parts")) {
                        JsonObject part = element.getAsJsonObject();
                        boolean thought = part.has("thought") && !part.get("thought").isJsonNull()
                                && part.get("thought").getAsBoolean();
                        if (!thought && part.has("text") && !part.get("text").isJsonNull()) {
                            if (answer.length() > 0) {
                                answer.append('\n');
                            }
                            answer.append(part.get("text").getAsString());
                        }
                    }
                }
            }

            if (answer.toString().isBlank()) {
                return Reply.failure("Gemini returned an empty response.");
            }
            return Reply.success(answer.toString().trim());
        } catch (Exception parseFailure) {
            return Reply.failure("Failed to parse Gemini response.");
        }
    }

    private static String mapStatusError(int status, String body) {
        String apiMessage = extractApiErrorMessage(body);
        if (status == 401 || status == 403) {
            return "Invalid or unauthorized API key (HTTP " + status + ").";
        }
        if (status == 429) {
            int retry = parseRetrySeconds(apiMessage, body);
            RequestGuard.pauseFor(retry);
            return "Free limit reached (HTTP 429). The mod pauses for " + retry
                    + " s so no quota is wasted. Cached answers still work.";
        }
        if (status >= 500) {
            return "Gemini server error (HTTP " + status + ").";
        }
        return "Request failed (HTTP " + status + "): " + truncate(apiMessage);
    }

    private static int parseRetrySeconds(String apiMessage, String body) {
        String text = (apiMessage == null ? "" : apiMessage) + " " + (body == null ? "" : body);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)retry(?:\\s*in|Delay\"?\\s*:\\s*\")\\s*(\\d+(?:\\.\\d+)?)\\s*s").matcher(text);
        if (matcher.find()) {
            try {
                return (int) Math.ceil(Double.parseDouble(matcher.group(1))) + 1;
            } catch (NumberFormatException ignored) {
                // use default
            }
        }
        return 30;
    }

    private static String extractApiErrorMessage(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("error") && json.get("error").isJsonObject()) {
                JsonObject error = json.getAsJsonObject("error");
                if (error.has("message") && !error.get("message").isJsonNull()) {
                    return error.get("message").getAsString();
                }
            }
        } catch (Exception ignored) {
        }
        return body == null ? "unknown error" : body;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String collapsed = value.replaceAll("\\s+", " ").trim();
        return collapsed.length() > 160 ? collapsed.substring(0, 157) + "..." : collapsed;
    }
}