package net.hasor.neta.codec.http;

import net.hasor.cobble.function.Release;
import net.hasor.neta.bytebuf.StringView;

final class HttpCharSequences {
    private HttpCharSequences() {
    }

    static String materialize(CharSequence value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof StringView) {
            StringView view = (StringView) value;
            String resolved = view.resolve();
            view.release();
            return resolved;
        }

        String resolved = value.toString();
        if (value instanceof Release) {
            ((Release) value).release();
        }
        return resolved;
    }

    static void release(CharSequence value) {
        if (value instanceof Release) {
            ((Release) value).release();
        }
    }

    static boolean isOwned(CharSequence value) {
        return value instanceof Release;
    }

    static boolean equalsIgnoreCase(CharSequence left, CharSequence right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (left.length() != right.length()) {
            return false;
        }
        for (int i = 0; i < left.length(); i++) {
            char c1 = left.charAt(i);
            char c2 = right.charAt(i);
            if (c1 == c2) {
                continue;
            }
            if (c1 >= 'A' && c1 <= 'Z') {
                c1 = (char) (c1 + 32);
            }
            if (c2 >= 'A' && c2 <= 'Z') {
                c2 = (char) (c2 + 32);
            }
            if (c1 != c2) {
                return false;
            }
        }
        return true;
    }

    static boolean containsIgnoreCase(CharSequence value, CharSequence needle) {
        if (value == null || needle == null) {
            return false;
        }
        int valueLength = value.length();
        int needleLength = needle.length();
        if (needleLength == 0) {
            return true;
        }
        if (needleLength > valueLength) {
            return false;
        }
        for (int start = 0; start <= valueLength - needleLength; start++) {
            int i = 0;
            while (i < needleLength) {
                char c1 = value.charAt(start + i);
                char c2 = needle.charAt(i);
                if (c1 != c2) {
                    if (c1 >= 'A' && c1 <= 'Z') {
                        c1 = (char) (c1 + 32);
                    }
                    if (c2 >= 'A' && c2 <= 'Z') {
                        c2 = (char) (c2 + 32);
                    }
                    if (c1 != c2) {
                        break;
                    }
                }
                i++;
            }
            if (i == needleLength) {
                return true;
            }
        }
        return false;
    }

    static long parseLong(CharSequence value) {
        if (value == null) {
            throw new NumberFormatException("null");
        }
        int start = 0;
        int end = value.length();
        while (start < end && Character.isWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        if (start >= end) {
            throw new NumberFormatException("blank");
        }

        long result = 0;
        for (int i = start; i < end; i++) {
            char ch = value.charAt(i);
            if (ch < '0' || ch > '9') {
                throw new NumberFormatException(value.toString());
            }
            result = result * 10 + (ch - '0');
        }
        return result;
    }

    static boolean isBlank(CharSequence value) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}