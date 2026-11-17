package com.capy.client.antivanish.core;

import java.util.Locale;
import java.util.regex.Pattern;

public final class VanishText {
    private VanishText() {}

    private static final Pattern NAME = Pattern.compile("^\\.?[A-Za-z0-9_]{3,16}$"); // a leading dot is a Bedrock player
    private static final String[] LEAVE_WORDS = {"left", "quit", "disconnect", "logged out", "leave"};

    public static boolean plausibleName(String name) { return name != null && NAME.matcher(name).matches(); }

    private static boolean wordChar(char c) { return Character.isLetterOrDigit(c) || c == '_'; }

    /** True when text contains the name as a whole word (case-insensitive). */
    public static boolean containsName(String text, String name) {
        if (text == null || name == null || name.isBlank()) return false;
        String t = text.toLowerCase(Locale.ROOT), n = name.toLowerCase(Locale.ROOT);
        for (int from = t.indexOf(n); from >= 0; from = t.indexOf(n, from + 1)) {
            boolean before = from == 0 || !wordChar(t.charAt(from - 1));
            int end = from + n.length();
            boolean after = end >= t.length() || !wordChar(t.charAt(end));
            if (before && after) return true;
        }
        return false;
    }

    public static boolean looksLikeLeave(String text, String name) {
        if (!containsName(text, name)) return false;
        String t = text.toLowerCase(Locale.ROOT);
        for (String w : LEAVE_WORDS) if (t.contains(w)) return true;
        return false;
    }
}
