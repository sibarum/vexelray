package dev.vexelray.trace;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * One trace site, as the weaver found it: what a row's id resolves to.
 *
 * @param id         the site's id, hashed or pinned ({@link Trace#id})
 * @param owner      the class, binary name with dots
 * @param method     the method's name
 * @param descriptor the method's descriptor, which tells overloads apart
 * @param file       the source file, or empty when the class was compiled without one
 * @param line       the method's first line, or 0 when the class was compiled without line numbers
 * @param label      what its rows are called: their kind is {@code trace.<label>}
 * @param timed      whether it also writes a {@code trace.exit} row
 * @param args       the arguments its rows record, in order
 */
public record Site(long id, String owner, String method, String descriptor, String file, int line, String label,
                   boolean timed, List<Arg> args) {

    /**
     * An argument a site records. {@code type} says how its row's raw {@code long} reads: a primitive's name,
     * {@code enum:<class>} for an ordinal, {@code string} for a length, {@code text} for a string written in full on
     * its own row.
     */
    public record Arg(String name, String type) {

        /** {@code raw}, as the row recorded it, read back as this argument's value. */
        public String format(long raw) {
            return switch (type) {
                case "boolean" -> raw != 0 ? "true" : "false";
                case "char" -> "'" + (char) raw + "'";
                case "float" -> Float.toString(Float.intBitsToFloat((int) raw));
                case "double" -> Double.toString(Double.longBitsToDouble(raw));
                case "string", "text" -> raw < 0 ? "null" : raw + " chars";
                default -> type.startsWith("enum:") ? (raw < 0 ? "null" : enumName(raw)) : Long.toString(raw);
            };
        }

        private String enumName(long ordinal) {
            String cls = type.substring("enum:".length());
            try {
                Object[] constants = Class.forName(cls, false, Site.class.getClassLoader()).getEnumConstants();
                if (constants != null && ordinal < constants.length) {
                    return constants[(int) ordinal].toString();
                }
            } catch (ClassNotFoundException | LinkageError e) {
                // read on a machine without the class: the ordinal is still the truth
            }
            return cls.substring(cls.lastIndexOf('.') + 1) + "#" + ordinal;
        }
    }

    /** The id as rows write it: sixteen hex digits. */
    public String key() {
        return hex(id);
    }

    /** {@code dev.example.Player.seek(Player.java:42)}: the line a stack trace would print for it. */
    public String where() {
        return owner + "." + method + "(" + (file.isEmpty() ? "Unknown Source" : file + (line > 0 ? ":" + line : ""))
                + ")";
    }

    /** The row kind this site writes as it is entered. */
    public String kind() {
        return "trace." + label;
    }

    static String hex(long id) {
        return HexFormat.of().toHexDigits(id);
    }

    // --- the table a module's generated TraceSites holds ------------------------------------------------------

    /** One line of a {@link TraceSites#table()}: tab-separated, the arguments last as {@code name:type,...}. */
    String tableLine() {
        StringBuilder a = new StringBuilder();
        for (Arg arg : args) {
            a.append(a.isEmpty() ? "" : ",").append(arg.name()).append(':').append(arg.type());
        }
        return String.join("\t", hex(id), owner, method, descriptor, file, Integer.toString(line), label,
                timed ? "1" : "0", a);
    }

    /** The sites in a {@link TraceSites#table()}, one a line; a line that is not one is an error in the table. */
    public static List<Site> parse(String table) {
        List<Site> sites = new ArrayList<>();
        for (String line : table.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split("\t", -1);
            if (f.length != 9) {
                throw new IllegalArgumentException("not a trace site table line: " + line);
            }
            List<Arg> args = new ArrayList<>();
            if (!f[8].isEmpty()) {
                for (String a : f[8].split(",")) {
                    int colon = a.indexOf(':');
                    args.add(new Arg(a.substring(0, colon), a.substring(colon + 1)));
                }
            }
            sites.add(new Site(HexFormat.fromHexDigitsToLong(f[0]), f[1], f[2], f[3], f[4], Integer.parseInt(f[5]),
                    f[6], f[7].equals("1"), List.copyOf(args)));
        }
        return sites;
    }

    /** {@code sites} as a table, the inverse of {@link #parse}: what the weaver writes into a module. */
    public static String table(List<Site> sites) {
        StringBuilder sb = new StringBuilder();
        for (Site s : sites) {
            sb.append(s.tableLine()).append('\n');
        }
        return sb.toString();
    }
}
