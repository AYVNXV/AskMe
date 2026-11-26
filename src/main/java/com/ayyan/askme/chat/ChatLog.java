package com.ayyan.askme.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared in-memory message list shown by the Ask Me chat window. Commands, the AI and the
 * tag-reply listener all write here, so the window always shows everything in one place.
 */
public final class ChatLog {

    public enum Kind { USER, AI, SYSTEM, TAG }

    public static final class Entry {
        public volatile Kind kind;
        public volatile String text;

        Entry(Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    private static final int MAX_ENTRIES = 200;
    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static int version;

    private ChatLog() {
    }

    public static synchronized Entry add(Kind kind, String text) {
        Entry entry = new Entry(kind, text == null ? "" : text);
        ENTRIES.add(entry);
        while (ENTRIES.size() > MAX_ENTRIES) {
            ENTRIES.remove(0);
        }
        version++;
        return entry;
    }

    public static synchronized void update(Entry entry, Kind kind, String text) {
        if (entry == null) {
            return;
        }
        entry.kind = kind;
        entry.text = text == null ? "" : text;
        version++;
    }

    public static synchronized List<Entry> snapshot() {
        return new ArrayList<>(ENTRIES);
    }

    public static synchronized int version() {
        return version;
    }

    public static synchronized boolean isEmpty() {
        return ENTRIES.isEmpty();
    }

    public static synchronized void clear() {
        ENTRIES.clear();
        version++;
    }
}
