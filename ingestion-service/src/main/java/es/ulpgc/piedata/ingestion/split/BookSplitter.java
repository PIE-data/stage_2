package es.ulpgc.piedata.ingestion.split;

import java.util.regex.Pattern;

public final class BookSplitter {

    public static final String START_MARKER = "*** START OF THE PROJECT GUTENBERG EBOOK";
    public static final String END_MARKER = "*** END OF THE PROJECT GUTENBERG EBOOK";

    private static final String BOM = "\uFEFF";
    private static final Pattern THREE_OR_MORE_NEWLINES = Pattern.compile("\\n{3,}");

    public SplitResult split(String text) {
        int start = text.indexOf(START_MARKER);
        if (start == -1) {
            throw new MarkersNotFoundException("START marker not found");
        }

        int end = text.lastIndexOf(END_MARKER);
        if (end == -1) {
            throw new MarkersNotFoundException("END marker not found");
        }

        int bodyStart = lineEnd(text, start);
        int bodyEnd = lineStart(text, end);
        if (bodyEnd < bodyStart) {
            throw new MarkersNotFoundException("END marker precedes START marker");
        }

        String rawHeader = text.substring(0, lineStart(text, start));
        String rawBody = text.substring(bodyStart, bodyEnd);
        return new SplitResult(cleanHeader(rawHeader), cleanBody(rawBody));
    }

    private static int lineStart(String text, int index) {
        int prevNl = text.lastIndexOf('\n', index - 1);
        return prevNl == -1 ? 0 : prevNl + 1;
    }

    private static int lineEnd(String text, int index) {
        int nextNl = text.indexOf('\n', index);
        return nextNl == -1 ? text.length() : nextNl + 1;
    }

    private static String normalizeNewlines(String text) {
        if (text.startsWith(BOM)) {
            text = text.substring(BOM.length());
        }
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    public static String cleanHeader(String header) {
        String text = normalizeNewlines(header).strip();
        return text.isEmpty() ? "" : text + "\n";
    }

    public static String cleanBody(String body) {
        String text = normalizeNewlines(body);
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int end = line.length();
            while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
                end--;
            }
            sb.append(line, 0, end);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        text = THREE_OR_MORE_NEWLINES.matcher(sb.toString()).replaceAll("\n\n");
        text = text.strip();
        return text.isEmpty() ? "" : text + "\n";
    }
}