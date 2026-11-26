package com.ayyan.askme.limit;

import com.ayyan.askme.config.AskMeConfig;

import java.util.ArrayDeque;

/**
 * Keeps the mod inside the free tier of the Gemini API.
 * - Sliding one-minute window: never more than "maxRequestsPerMinute" requests.
 * - After HTTP 429 no new request is sent until the retry time has passed.
 */
public final class RequestGuard {

    private static final long WINDOW_MS = 60_000L;
    private static final ArrayDeque<Long> SENT = new ArrayDeque<>();
    private static long pausedUntil;
    private static long totalSent;

    private RequestGuard() {
    }

    /** 0 = a request may be sent now, otherwise the seconds to wait. */
    public static synchronized int secondsUntilAllowed() {
        long now = System.currentTimeMillis();
        trim(now);
        long wait = pausedUntil > now ? pausedUntil - now : 0;
        if (SENT.size() >= AskMeConfig.get().maxRequestsPerMinute) {
            wait = Math.max(wait, WINDOW_MS - (now - SENT.peekFirst()));
        }
        return wait <= 0 ? 0 : (int) Math.ceil(wait / 1000.0);
    }

    public static synchronized void recordSent() {
        SENT.addLast(System.currentTimeMillis());
        totalSent++;
    }

    public static synchronized void pauseFor(int seconds) {
        long until = System.currentTimeMillis() + Math.max(1, Math.min(seconds, 300)) * 1000L;
        pausedUntil = Math.max(pausedUntil, until);
    }

    public static synchronized int usedInLastMinute() {
        trim(System.currentTimeMillis());
        return SENT.size();
    }

    public static synchronized long totalSent() {
        return totalSent;
    }

    private static void trim(long now) {
        while (!SENT.isEmpty() && now - SENT.peekFirst() > WINDOW_MS) {
            SENT.pollFirst();
        }
    }
}
