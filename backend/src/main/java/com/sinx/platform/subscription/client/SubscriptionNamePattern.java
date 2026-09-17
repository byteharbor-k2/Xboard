package com.sinx.platform.subscription.client;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * How a template names a set of nodes.
 *
 * The templates grew two ways of writing "the Hong Kong ones", and a renderer
 * has to honour both without confusing either for a node's actual name. In a
 * Clash proxy group a member is a pattern only when it is delimited, because
 * {@code 香港} is a perfectly good node name and a perfectly good regex at the
 * same time; sing-box does not have that ambiguity to resolve, so it reads
 * every {@code include} as a pattern and wraps the ones that are not already
 * delimited.
 *
 * Neither rule is worth simplifying. A node called {@code DIRECT} that got
 * swept up by a pattern would vanish from the config, and a pattern read as a
 * name would take a whole group of nodes with it.
 */
final class SubscriptionNamePattern {

    /** Delimiters that PCRE closes with a different character than they open with. */
    private static final Map<Character, Character> PAIRED = Map.of(
        '(', ')',
        '[', ']',
        '{', '}',
        '<', '>'
    );

    /** The delimiters sing-box recognises before it decides to wrap a pattern itself. */
    private static final String SING_BOX_DELIMITERS = "/#~@%";

    private SubscriptionNamePattern() {
    }

    /**
     * Whether a Clash proxy-group member is a pattern rather than a node name.
     *
     * Deliberately syntactic. The original asks its regex engine whether the
     * string compiles, which for a bare name it does - "香港" is a valid pattern
     * matching "香港" - and only the missing delimiters make it false.
     */
    static boolean isDelimited(String candidate) {
        if (candidate == null || candidate.length() < 2) {
            return false;
        }
        char open = candidate.charAt(0);
        if (
            Character.isLetterOrDigit(open)
                || open == '\\'
                || Character.isWhitespace(open)
        ) {
            return false;
        }
        char close = PAIRED.getOrDefault(open, open);
        int closing = candidate.lastIndexOf(close);
        if (closing <= 0) {
            return false;
        }
        for (int index = closing + 1; index < candidate.length(); index++) {
            if (!Character.isLetter(candidate.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /** Whether a delimited pattern matches anywhere in a node name. */
    static boolean matches(String delimited, String subject) {
        try {
            return compile(delimited).matcher(subject).find();
        } catch (PatternSyntaxException exception) {
            // The original lets a broken pattern match nothing and carry on.
            return false;
        }
    }

    /**
     * Whether a sing-box {@code include} or {@code exclude} matches anywhere in
     * a node tag. A bare pattern is made case-insensitive, as there is no
     * delimiter for the author to hang modifiers off.
     */
    static boolean matchesBare(String pattern, String subject) {
        String trimmed = pattern == null ? "" : pattern.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String delimited = looksDelimited(trimmed)
            ? trimmed
            : "~" + trimmed.replace("~", "\\~") + "~ui";
        return matches(delimited, subject);
    }

    private static boolean looksDelimited(String candidate) {
        char open = candidate.charAt(0);
        if (SING_BOX_DELIMITERS.indexOf(open) < 0) {
            return false;
        }
        char close = PAIRED.getOrDefault(open, open);
        int closing = candidate.lastIndexOf(close);
        if (closing <= 0) {
            return false;
        }
        for (int index = closing + 1; index < candidate.length(); index++) {
            if (!Character.isLetter(candidate.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static Pattern compile(String delimited) {
        char open = delimited.charAt(0);
        char close = PAIRED.getOrDefault(open, open);
        int closing = delimited.lastIndexOf(close);
        String body = delimited.substring(1, closing);
        String modifiers = delimited.substring(closing + 1);
        int flags = 0;
        if (modifiers.indexOf('i') >= 0) {
            flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        }
        if (modifiers.indexOf('m') >= 0) {
            flags |= Pattern.MULTILINE;
        }
        if (modifiers.indexOf('s') >= 0) {
            flags |= Pattern.DOTALL;
        }
        if (modifiers.indexOf('x') >= 0) {
            flags |= Pattern.COMMENTS;
        }
        // The 'u' modifier means the pattern is UTF-8, which every Java string
        // already is; any other modifier the original accepts has no Java
        // equivalent and is ignored rather than treated as a broken pattern.
        return Pattern.compile(body, flags);
    }
}
