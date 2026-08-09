package dev.vexelray.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal recursive-descent JSON parser. Output types: object → {@code Map<String,Object>}, array →
 * {@code List<Object>}, string → {@code String}, number → {@code Double}, true/false → {@code Boolean}, null →
 * {@code null}. Reflection-free; the msdf-atlas-gen JSON we consume is well under a MB.
 *
 * <p>Copied from Dasum (sibarum.dasum.gui.core.json.Json) — VexelRay has no JSON dependency and this atlas format
 * is the only JSON it reads.
 */
public final class Json {

    private final String src;
    private int i;

    private Json(String src) {
        this.src = src;
        this.i = 0;
    }

    public static Object parse(String src) {
        Json p = new Json(src);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (p.i != p.src.length()) {
            throw p.err("trailing content");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String src) {
        Object v = parse(src);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("Top-level JSON value is not an object");
        }
        return (Map<String, Object>) v;
    }

    private Object readValue() {
        skipWs();
        if (i >= src.length()) {
            throw err("unexpected end of input");
        }
        char c = src.charAt(i);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't', 'f' -> readBool();
            case 'n' -> readNull();
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield readNumber();
                }
                throw err("unexpected character '" + c + "'");
            }
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> m = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            skipWs();
            String key = readString();
            skipWs();
            expect(':');
            Object val = readValue();
            m.put(key, val);
            skipWs();
            char c = src.charAt(i++);
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw err("expected ',' or '}' after object entry");
            }
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> l = new ArrayList<>();
        skipWs();
        if (peek() == ']') {
            i++;
            return l;
        }
        while (true) {
            l.add(readValue());
            skipWs();
            char c = src.charAt(i++);
            if (c == ']') {
                return l;
            }
            if (c != ',') {
                throw err("expected ',' or ']' after array element");
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (i < src.length()) {
            char c = src.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (i >= src.length()) {
                    throw err("dangling escape");
                }
                char e = src.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > src.length()) {
                            throw err("short \\u escape");
                        }
                        sb.append((char) Integer.parseInt(src.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw err("invalid escape '\\" + e + "'");
                }
            } else {
                sb.append(c);
            }
        }
        throw err("unterminated string");
    }

    private Double readNumber() {
        int start = i;
        if (src.charAt(i) == '-') {
            i++;
        }
        while (i < src.length() && isDigit(src.charAt(i))) {
            i++;
        }
        if (i < src.length() && src.charAt(i) == '.') {
            i++;
            while (i < src.length() && isDigit(src.charAt(i))) {
                i++;
            }
        }
        if (i < src.length() && (src.charAt(i) == 'e' || src.charAt(i) == 'E')) {
            i++;
            if (i < src.length() && (src.charAt(i) == '+' || src.charAt(i) == '-')) {
                i++;
            }
            while (i < src.length() && isDigit(src.charAt(i))) {
                i++;
            }
        }
        return Double.parseDouble(src.substring(start, i));
    }

    private Boolean readBool() {
        if (src.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        }
        throw err("expected true/false");
    }

    private Object readNull() {
        if (src.startsWith("null", i)) {
            i += 4;
            return null;
        }
        throw err("expected null");
    }

    private void skipWs() {
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else {
                break;
            }
        }
    }

    private void expect(char c) {
        if (i >= src.length() || src.charAt(i) != c) {
            throw err("expected '" + c + "'");
        }
        i++;
    }

    private char peek() {
        if (i >= src.length()) {
            throw err("unexpected end of input");
        }
        return src.charAt(i);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private RuntimeException err(String msg) {
        int line = 1;
        int col = 1;
        for (int k = 0; k < i && k < src.length(); k++) {
            if (src.charAt(k) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new IllegalArgumentException("JSON parse error at line " + line + " col " + col + ": " + msg);
    }
}
