package es.ulpgc.piedata.common.metadata;

import java.util.ArrayList;
import java.util.List;

/**
 * The few Python string operations the Stage 1 header parser relies on, reproduced exactly so the
 * Java parser gives the same records. Java's own {@code String.lines()}, {@code strip()} and
 * {@code Character.isWhitespace} use different character sets (e.g. Java does not treat the
 * no-break space U+00A0 as whitespace, Python does).
 */
final class PyText {

    private PyText() {
    }

    /** Python {@code str.isspace()} for a single code point. */
    static boolean isSpace(int cp) {
        switch (cp) {
            case '\t': case '\n': case 0x0B: case 0x0C: case '\r':
            case 0x1C: case 0x1D: case 0x1E: case 0x1F:
            case ' ': case 0x85: case 0xA0: case 0x1680:
            case 0x2028: case 0x2029: case 0x202F: case 0x205F: case 0x3000:
                return true;
            default:
                return cp >= 0x2000 && cp <= 0x200A;
        }
    }

    /** Python {@code str.splitlines()}: no line terminators kept, no empty last line. */
    static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (isLineBreak(c)) {
                lines.add(text.substring(start, i));
                i += (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') ? 2 : 1;
                start = i;
            } else {
                i++;
            }
        }
        if (start < n) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    private static boolean isLineBreak(char c) {
        switch (c) {
            case '\n': case '\r': case 0x0B: case 0x0C:
            case 0x1C: case 0x1D: case 0x1E: case 0x85: case 0x2028: case 0x2029:
                return true;
            default:
                return false;
        }
    }

    /** Python {@code str.strip()}. */
    static String strip(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isSpace(s.codePointAt(start))) {
            start += Character.charCount(s.codePointAt(start));
        }
        while (end > start && isSpace(s.codePointBefore(end))) {
            end -= Character.charCount(s.codePointBefore(end));
        }
        return s.substring(start, end);
    }

    /** Python {@code str.lstrip()}. */
    static String lstrip(String s) {
        int start = 0;
        while (start < s.length() && isSpace(s.codePointAt(start))) {
            start += Character.charCount(s.codePointAt(start));
        }
        return s.substring(start);
    }

    /** Python {@code " ".join(s.split())}: whitespace runs become one space, ends trimmed. */
    static String collapseWhitespace(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean pendingSpace = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isSpace(cp)) {
                pendingSpace = out.length() > 0;
            } else {
                if (pendingSpace) {
                    out.append(' ');
                    pendingSpace = false;
                }
                out.appendCodePoint(cp);
            }
        }
        return out.toString();
    }

    /** True when the string is empty or only whitespace (Python {@code not s.strip()}). */
    static boolean isBlank(String s) {
        return s.codePoints().allMatch(PyText::isSpace);
    }
}
