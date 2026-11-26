package com.ayyan.askme.chat;

import com.ayyan.askme.AskMe;
import com.ayyan.askme.config.AskMeConfig;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes your own name easy to see: wherever it appears in chat (player chat, kills, deaths, server text)
 * it gets your colour / bold / italic / underline, and a game sound plays.
 *
 * <p>How it works: when a message contains your name, the original line is hidden and the same line is
 * shown again with the styled name (all other colours, click and hover events are kept).
 * Messages without your name are never touched.</p>
 */
public final class NameHighlighter {

    private static final long SOUND_GAP_MS = 1500L;
    private static long lastSoundAt;

    /** Last chat / system lines (used to explain a death). Only kept in memory. */
    private static final ArrayDeque<String> RECENT = new ArrayDeque<>();
    private static final int RECENT_MAX = 14;

    /** Highlighted copies we sent ourselves. When one comes back through the events, it must be ignored. */
    private static final ArrayDeque<String[]> OWN_COPIES = new ArrayDeque<>();
    private static final long OWN_COPY_TTL_MS = 3000L;
    private static boolean emitting;

    private static String cachedIgn;
    private static Pattern cachedPattern;

    private NameHighlighter() {
    }

    public static void initialize() {
        // Plain game/system text (kills, deaths, many server chats).
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (overlay) {
                return true;
            }
            return !handle(message, null, false);
        });
        // Signed player chat.
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, time) ->
                !handle(message, sender == null ? null : sender.name(), true));
    }

    /** Returns true when the message was replaced by a highlighted copy (the original must be hidden). */
    private static boolean handle(Component message, String trustedSender, boolean signedChat) {
        try {
            return process(message, trustedSender, signedChat);
        } catch (Throwable problem) {
            // A chat listener must never crash the game or the server connection.
            AskMe.LOGGER.warn("[Ask Me] Name highlight failed, message left unchanged: {}", problem.toString());
            emitting = false;
            return false;
        }
    }

    private static boolean process(Component message, String trustedSender, boolean signedChat) {
        AskMeConfig config = AskMeConfig.get();
        Minecraft client = Minecraft.getInstance();
        if (message == null || client.player == null) {
            return false;
        }
        String ign = client.player.getName().getString();
        if (ign.isBlank()) {
            return false;
        }
        String line = message.getString();
        if (line == null || line.contains("[Ask Me]")) {
            return false;
        }
        // Loop protection: never process the copy that we sent ourselves.
        if (isOwnCopy(line) || emitting) {
            return false;
        }

        remember(line);
        Pattern pattern = namePattern(ign);
        boolean mentioned = pattern.matcher(line).find();
        if (!mentioned) {
            return false;
        }

        // Sound: only when someone else wrote it or when it is not a normal chat line of ours.
        String[] parts = TagReplyListener.splitSpeaker(line);
        boolean ownLine = (parts != null && parts[0].equalsIgnoreCase(ign))
                || (trustedSender != null && trustedSender.equalsIgnoreCase(ign));
        if (!ownLine) {
            playSound(client, config);
        }

        boolean replaced = false;
        if (config.nameHighlight) {
            Component styled = highlight(message, pattern, config);
            if (styled != null) {
                markOwnCopy(styled.getString());
                emitting = true;
                try {
                    client.player.sendSystemMessage(styled);
                } finally {
                    emitting = false;
                }
                replaced = true;
            }
        }
        // The tag reply logic gets every message that contains your name (it is not registered on its own,
        // because a hidden original would never reach it).
        TagReplyListener.onMessage(message, trustedSender, signedChat);
        return replaced;
    }

    private static synchronized void remember(String line) {
        String clean = line.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) {
            return;
        }
        RECENT.addLast(clean.length() > 160 ? clean.substring(0, 160) : clean);
        while (RECENT.size() > RECENT_MAX) {
            RECENT.pollFirst();
        }
    }

    /** Newest line last. */
    public static synchronized List<String> recentLines() {
        return new ArrayList<>(RECENT);
    }

    private static synchronized void markOwnCopy(String line) {
        long now = System.currentTimeMillis();
        pruneOwnCopies(now);
        OWN_COPIES.addLast(new String[] {line, Long.toString(now)});
        while (OWN_COPIES.size() > 8) {
            OWN_COPIES.pollFirst();
        }
    }

    private static synchronized boolean isOwnCopy(String line) {
        pruneOwnCopies(System.currentTimeMillis());
        var it = OWN_COPIES.iterator();
        while (it.hasNext()) {
            if (it.next()[0].equals(line)) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    private static void pruneOwnCopies(long now) {
        while (!OWN_COPIES.isEmpty() && now - Long.parseLong(OWN_COPIES.peekFirst()[1]) > OWN_COPY_TTL_MS) {
            OWN_COPIES.pollFirst();
        }
    }

    private static synchronized Pattern namePattern(String ign) {
        if (cachedPattern == null || !ign.equals(cachedIgn)) {
            cachedPattern = Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(ign) + "(?![A-Za-z0-9_])");
            cachedIgn = ign;
        }
        return cachedPattern;
    }

    /** Rebuilds the message with the name styled. Returns null if nothing was changed. */
    static Component highlight(Component message, Pattern pattern, AskMeConfig config) {
        MutableComponent out = Component.empty();
        boolean[] found = {false};
        message.visit((style, text) -> {
            Matcher m = pattern.matcher(text);
            int last = 0;
            while (m.find()) {
                found[0] = true;
                if (m.start() > last) {
                    out.append(piece(text.substring(last, m.start()), style));
                }
                out.append(piece(m.group(), nameStyle(style, config)));
                last = m.end();
            }
            if (last < text.length()) {
                out.append(piece(text.substring(last), style));
            }
            return Optional.empty();
        }, Style.EMPTY);
        return found[0] ? out : null;
    }

    private static MutableComponent piece(String text, Style style) {
        return Component.literal(text).withStyle(ignored -> style);
    }

    /** The style used for the name. Also used by the style screen preview. */
    public static Style nameStyle(Style base, AskMeConfig config) {
        int rgb = parseColor(config.highlightColor);
        return base.withColor(rgb)
                .withBold(config.highlightBold)
                .withItalic(config.highlightItalic)
                .withUnderlined(config.highlightUnderline);
    }

    public static int parseColor(String hex) {
        try {
            return Integer.parseInt(hex.replace("#", ""), 16) & 0xFFFFFF;
        } catch (RuntimeException e) {
            return 0xFFD54F;
        }
    }

    // ------------------------------------------------------------------ sound

    private static void playSound(Minecraft client, AskMeConfig config) {
        long now = System.currentTimeMillis();
        if (now - lastSoundAt < SOUND_GAP_MS) {
            return;
        }
        lastSoundAt = now;
        playSoundNow(client, config.nameSound, config.nameSoundVolume);
    }

    /** Plays any game sound by id (for example block.note_block.pling). Also used by the test button. */
    public static void playSoundNow(Minecraft client, String soundId, int volumePercent) {
        if (client == null || soundId == null || soundId.isBlank() || soundId.equalsIgnoreCase("off")) {
            return;
        }
        try {
            String raw = soundId.trim().toLowerCase(Locale.ROOT);
            Identifier id = Identifier.tryParse(raw.contains(":") ? raw : "minecraft:" + raw);
            if (id == null) {
                return;
            }
            SoundEvent event = SoundEvent.createVariableRangeEvent(id);
            float volume = Math.max(0f, Math.min(1f, volumePercent / 100f));
            client.getSoundManager().play(SimpleSoundInstance.forUI(event, 1.0f, volume));
        } catch (Throwable problem) {
            AskMe.LOGGER.debug("[Ask Me] Could not play sound {}: {}", soundId, problem.toString());
        }
    }
}
