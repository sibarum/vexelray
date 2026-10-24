package dev.vexelray.trace.weave;

import java.io.IOException;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.LocalVariableTableAttribute;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Weaves a directory of compiled classes: every method annotated {@code @dev.vexelray.trace.Trace} is rewritten to
 * begin with a guarded call into {@code dev.vexelray.trace.Tracing}, and the module gets a generated
 * {@code TraceSites} naming its sites, listed in {@code META-INF/services}. See {@code docs/trace.md}.
 *
 * <p>On the JDK's ClassFile API ({@code java.lang.classfile}), with no dependencies. Everything is decided here, at
 * build time: a woven class reflects on nothing and runs nothing at load.
 *
 * <p>Weaving twice is harmless. An incremental build leaves classes woven by the last one in place, and a method
 * whose code already begins with the guard is not woven again, though its site is still listed.
 */
public final class Weaver {

    /** At most this many arguments a site records: one {@code Tracing.enter} overload each. */
    public static final int MAX_ARGS = 4;

    static final String TRACE = "Ldev/vexelray/trace/Trace;";
    static final ClassDesc TRACING = ClassDesc.of("dev.vexelray.trace.Tracing");
    static final ClassDesc TRACE_SITES = ClassDesc.of("dev.vexelray.trace.TraceSites");
    static final String SERVICE = "META-INF/services/dev.vexelray.trace.TraceSites";
    private static final ClassDesc CD_ENUM = ClassDesc.of("java.lang.Enum");

    /** One site found, before it is written into the table. Mirrors {@code dev.vexelray.trace.Site}'s line. */
    public record Found(long id, String owner, String method, String descriptor, String file, int line, String label,
                        boolean timed, List<String[]> args, List<String> paramNames) {

        /** The kind of the row it writes on entry, baked into the woven code as a constant. */
        String kind() {
            return "trace." + label;
        }

        String tableLine() {
            StringBuilder a = new StringBuilder();
            for (String[] arg : args) {
                a.append(a.isEmpty() ? "" : ",").append(arg[0]).append(':').append(arg[1]);
            }
            return String.join("\t", HexFormat.of().toHexDigits(id), owner, method, descriptor, file,
                    Integer.toString(line), label, timed ? "1" : "0", a);
        }

        String where() {
            return owner + "." + method + "(" + (file.isEmpty() ? "Unknown Source" : file + ":" + line) + ")";
        }
    }

    /** What a weave did: the sites it found, how many methods it rewrote, and the table class it wrote. */
    public record Result(List<Found> sites, int woven, String sitesClass) {
    }

    /** A weave that found mistakes: every one, so one build reports them all. */
    public static final class WeaveException extends Exception {
        private final List<String> problems;

        WeaveException(List<String> problems) {
            super(problems.size() + " trace site problem" + (problems.size() == 1 ? "" : "s") + ":\n  "
                    + String.join("\n  ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    private Weaver() {
    }

    /**
     * Weave {@code classes} in place.
     *
     * @param classes the compiled classes, a module's output directory
     * @param module  the module's name, which names its table class
     * @param loader  sees {@code classes} and the module's compile class path: what tells an enum from any other
     *                class, and what the stack maps are computed against
     */
    public static Result weave(Path classes, String module, ClassLoader loader) throws IOException, WeaveException {
        ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                ClassHierarchyResolver.defaultResolver().orElse(ClassHierarchyResolver.ofResourceParsing(loader))));
        List<String> problems = new ArrayList<>();
        List<Found> sites = new ArrayList<>();
        Map<Path, byte[]> rewritten = new LinkedHashMap<>();
        int woven = 0;
        int major = 0;
        int minor = 0;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(classes)) {
            files = walk.filter(p -> p.toString().endsWith(".class")).sorted().toList();
        }
        for (Path file : files) {
            ClassModel cm = cf.parse(Files.readAllBytes(file));
            Map<String, Found> plan = new LinkedHashMap<>();
            Set<String> already = new LinkedHashSet<>();
            for (MethodModel m : cm.methods()) {
                Optional<Map<String, AnnotationValue>> trace = traceOf(m);
                if (trace.isEmpty()) {
                    continue;
                }
                Found site = site(cm, m, trace.get(), loader, problems);
                if (site == null) {
                    continue;
                }
                sites.add(site);
                String key = m.methodName().stringValue() + m.methodType().stringValue();
                if (alreadyWoven(m)) {
                    already.add(key);
                } else {
                    plan.put(key, site);
                }
            }
            if (!plan.isEmpty() || !already.isEmpty()) {
                major = Math.max(major, cm.majorVersion());
                minor = cm.majorVersion() == major ? cm.minorVersion() : minor;
            }
            if (plan.isEmpty()) {
                continue;
            }
            ClassTransform transform = (clb, ce) -> {
                if (ce instanceof MethodModel mm) {
                    Found site = plan.get(mm.methodName().stringValue() + mm.methodType().stringValue());
                    if (site != null) {
                        clb.transformMethod(mm, MethodTransform.transformingCode(new Prologue(site, mm)));
                        return;
                    }
                }
                clb.with(ce);
            };
            rewritten.put(file, cf.transformClass(cm, transform));
            woven += plan.size();
        }

        Map<Long, Found> byId = new LinkedHashMap<>();
        for (Found s : sites) {
            Found other = byId.putIfAbsent(s.id(), s);
            if (other != null) {
                problems.add("two sites share the id " + HexFormat.of().toHexDigits(s.id()) + ": " + other.where()
                        + " and " + s.where() + "; give one another @Trace(id = ...)");
            }
        }
        if (!problems.isEmpty()) {
            throw new WeaveException(problems);
        }
        for (Map.Entry<Path, byte[]> e : rewritten.entrySet()) {
            Files.write(e.getKey(), e.getValue());
        }
        String sitesClass = null;
        if (!sites.isEmpty()) {
            sitesClass = tableClassName(sites, module);
            writeTable(cf, classes, sitesClass, sites, major, minor);
        }
        return new Result(List.copyOf(sites), woven, sitesClass);
    }

    // --- reading a site ---------------------------------------------------------------------------------------

    /** The {@code @Trace} on {@code m}, as its elements by name, or empty. */
    private static Optional<Map<String, AnnotationValue>> traceOf(MethodModel m) {
        return m.findAttribute(Attributes.runtimeInvisibleAnnotations()).flatMap(a -> a.annotations().stream()
                .filter(an -> an.className().stringValue().equals(TRACE))
                .findFirst()
                .map(an -> {
                    Map<String, AnnotationValue> els = new LinkedHashMap<>();
                    for (AnnotationElement el : an.elements()) {
                        els.put(el.name().stringValue(), el.value());
                    }
                    return els;
                }));
    }

    private static Found site(ClassModel cm, MethodModel m, Map<String, AnnotationValue> trace, ClassLoader loader,
                              List<String> problems) {
        String owner = cm.thisClass().asInternalName().replace('/', '.');
        String name = m.methodName().stringValue();
        String descriptor = m.methodType().stringValue();
        String file = cm.findAttribute(Attributes.sourceFile()).map(s -> s.sourceFile().stringValue()).orElse("");
        Optional<CodeModel> code = m.code();
        int line = code.map(Weaver::firstLine).orElse(0);
        String where = owner + "." + name + "(" + (file.isEmpty() ? "Unknown Source" : file + ":" + line) + ")";
        if (code.isEmpty()) {
            problems.add(where + ": @Trace on a method with no body");
            return null;
        }
        String label = stringOf(trace.get("value"));
        if (label == null || label.isEmpty()) {
            label = name;
        }
        if (label.chars().anyMatch(c -> Character.isWhitespace(c) || c == ',' || c == '\t')) {
            problems.add(where + ": the label '" + label + "' has whitespace or a comma; a row's kind cannot");
            return null;
        }
        boolean content = booleanOf(trace.get("content"));
        boolean timed = booleanOf(trace.get("timed"));
        long pinned = trace.get("id") instanceof AnnotationValue.OfLong l ? l.longValue() : 0L;
        List<String> argNames = trace.get("args") instanceof AnnotationValue.OfArray arr
                ? arr.values().stream().map(Weaver::stringOf).toList() : List.of();

        boolean isStatic = m.flags().has(AccessFlag.STATIC);
        MethodTypeDesc type = m.methodTypeSymbol();
        List<String> paramNames = parameterNames(m, code.get(), type, isStatic);
        if (argNames.size() > MAX_ARGS) {
            problems.add(where + ": records " + argNames.size() + " arguments; a site records at most " + MAX_ARGS);
        }
        List<String[]> args = new ArrayList<>();
        boolean anyString = false;
        for (String arg : argNames) {
            int index = paramNames == null ? -1 : paramNames.indexOf(arg);
            if (index < 0) {
                problems.add(where + ": args names '" + arg + "', which is not one of its parameters"
                        + (paramNames == null ? " (the class has no parameter names: compile with -g or -parameters)"
                        : " " + paramNames));
                continue;
            }
            ClassDesc p = type.parameterType(index);
            String kind = argType(p, content, loader);
            if (kind == null) {
                problems.add(where + ": args names '" + arg + "', a " + binaryName(p)
                        + "; a site records primitives, enums and strings only, since recording an object keeps it"
                        + " alive and records state that may change after the row is written");
                continue;
            }
            anyString |= kind.equals("string") || kind.equals("text");
            args.add(new String[]{arg, kind});
        }
        if (content && !anyString) {
            problems.add(where + ": content = true, and none of its args is a String");
        }
        long id = pinned != 0L ? pinned : hash(owner + "." + name + descriptor);
        return new Found(id, owner, name, descriptor, file, line, label, timed, List.copyOf(args),
                paramNames == null ? List.of() : paramNames);
    }

    /** How a parameter of type {@code p} is recorded, or {@code null} when it may not be. */
    static String argType(ClassDesc p, boolean content, ClassLoader loader) {
        if (p.isPrimitive()) {
            return p.displayName();
        }
        if (p.equals(ConstantDescs.CD_String)) {
            return content ? "text" : "string";
        }
        if (p.isClassOrInterface()) {
            String binary = binaryName(p);
            try {
                if (Class.forName(binary, false, loader).isEnum()) {
                    return "enum:" + binary;
                }
            } catch (ClassNotFoundException | LinkageError e) {
                return null;
            }
        }
        return null;
    }

    /** {@code java.util.List}, {@code a.B$C}: what {@link Class#forName} takes, and what a message should name. */
    private static String binaryName(ClassDesc p) {
        String d = p.descriptorString();
        return p.isClassOrInterface() ? d.substring(1, d.length() - 1).replace('/', '.') : p.displayName();
    }

    /** The method's parameter names, by position: from MethodParameters, else the local variable table, else null. */
    private static List<String> parameterNames(MethodModel m, CodeModel code, MethodTypeDesc type, boolean isStatic) {
        Optional<MethodParametersAttribute> mp = m.findAttribute(Attributes.methodParameters());
        if (mp.isPresent() && mp.get().parameters().size() == type.parameterCount()
                && mp.get().parameters().stream().allMatch(p -> p.name().isPresent())) {
            return mp.get().parameters().stream().map(p -> p.name().orElseThrow().stringValue()).toList();
        }
        Map<Integer, String> bySlot = new LinkedHashMap<>();
        code.findAttribute(Attributes.localVariableTable()).map(LocalVariableTableAttribute::localVariables)
                .ifPresent(vars -> vars.forEach(v -> {
                    if (v.startPc() == 0) {
                        bySlot.put(v.slot(), v.name().stringValue());
                    }
                }));
        for (CodeElement e : code) {
            if (e instanceof LocalVariable v && !bySlot.containsKey(v.slot())) {
                bySlot.put(v.slot(), v.name().stringValue());
            }
        }
        List<String> names = new ArrayList<>();
        int slot = isStatic ? 0 : 1;
        for (ClassDesc p : type.parameterList()) {
            String n = bySlot.get(slot);
            if (n == null) {
                return null;
            }
            names.add(n);
            slot += TypeKind.from(p).slotSize();
        }
        return names;
    }

    private static int firstLine(CodeModel code) {
        for (CodeElement e : code) {
            if (e instanceof LineNumber ln) {
                return ln.line();
            }
        }
        return 0;
    }

    /**
     * Whether {@code m} was woven by an earlier build: it reads the guard. Anywhere, not only first, since a timed
     * site sets its clock before it; and nothing but woven code reads it, as {@code Tracing} says.
     */
    private static boolean alreadyWoven(MethodModel m) {
        for (CodeElement e : m.code().orElseThrow()) {
            if (e instanceof FieldInstruction f && f.owner().asSymbol().equals(TRACING)
                    && f.name().stringValue().equals("ON")) {
                return true;
            }
        }
        return false;
    }

    /** A site's default id: the first eight bytes of the SHA-1 of {@code key}, never 0. */
    static long hash(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            long id = 0L;
            for (int i = 0; i < 8; i++) {
                id = id << 8 | (d[i] & 0xFFL);
            }
            return id == 0L ? 1L : id;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required of every JDK", e);
        }
    }

    private static String stringOf(AnnotationValue v) {
        return v instanceof AnnotationValue.OfString s ? s.stringValue() : null;
    }

    private static boolean booleanOf(AnnotationValue v) {
        return v instanceof AnnotationValue.OfBoolean b && b.booleanValue();
    }

    // --- the rewrite ------------------------------------------------------------------------------------------

    /**
     * The code a site begins with, and for a timed site what it ends with:
     * <pre>
     *   if (Tracing.ON) { Tracing.enter("trace.label", id, a0, ...); [Tracing.text(id, s) ...] [started = nanoTime()] }
     *   ... the method, each return preceded by  if (Tracing.ON) Tracing.exit(id, started, false)
     *   catch (Throwable t) { if (Tracing.ON) Tracing.exit(id, started, true); throw t; }
     * </pre>
     */
    private static final class Prologue implements CodeTransform {
        private final Found site;
        private final MethodTypeDesc type;
        private final boolean isStatic;
        private int started = -1;
        private Label tryStart;

        Prologue(Found site, MethodModel m) {
            this.site = site;
            this.type = m.methodTypeSymbol();
            this.isStatic = m.flags().has(AccessFlag.STATIC);
        }

        @Override
        public void atStart(CodeBuilder b) {
            if (site.timed()) {
                started = b.allocateLocal(TypeKind.LONG);
                b.lconst_0();
                b.lstore(started);
            }
            Label skip = b.newLabel();
            b.getstatic(TRACING, "ON", ConstantDescs.CD_boolean);
            b.ifeq(skip);
            b.ldc(site.kind());
            b.loadConstant(site.id());
            List<ClassDesc> enterParams = new ArrayList<>(List.of(ConstantDescs.CD_String, ConstantDescs.CD_long));
            for (String[] arg : site.args()) {
                int index = paramIndex(arg[0]);
                ClassDesc p = type.parameterType(index);
                b.loadLocal(TypeKind.from(p), slotOf(index));
                widen(b, p, arg[1]);
                enterParams.add(ConstantDescs.CD_long);
            }
            b.invokestatic(TRACING, "enter", MethodTypeDesc.of(ConstantDescs.CD_void, enterParams));
            for (String[] arg : site.args()) {
                if (arg[1].equals("text")) {
                    b.loadConstant(site.id());
                    b.aload(slotOf(paramIndex(arg[0])));
                    b.invokestatic(TRACING, "text", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_long,
                            ConstantDescs.CD_String));
                }
            }
            if (site.timed()) {
                b.invokestatic(ClassDesc.of("java.lang.System"), "nanoTime", MethodTypeDesc.of(ConstantDescs.CD_long));
                b.lstore(started);
            }
            b.labelBinding(skip);
            if (site.timed()) {
                tryStart = b.newBoundLabel();
            }
        }

        @Override
        public void accept(CodeBuilder b, CodeElement e) {
            if (site.timed() && e instanceof ReturnInstruction) {
                exit(b, false);
            }
            b.with(e);
        }

        @Override
        public void atEnd(CodeBuilder b) {
            if (!site.timed()) {
                return;
            }
            Label end = b.newBoundLabel();
            Label handler = b.newBoundLabel();
            int thrown = b.allocateLocal(TypeKind.REFERENCE);
            b.astore(thrown);
            exit(b, true);
            b.aload(thrown);
            b.athrow();
            b.exceptionCatchAll(tryStart, end, handler);
        }

        private void exit(CodeBuilder b, boolean threw) {
            Label skip = b.newLabel();
            b.getstatic(TRACING, "ON", ConstantDescs.CD_boolean);
            b.ifeq(skip);
            b.loadConstant(site.id());
            b.lload(started);
            if (threw) {
                b.iconst_1();
            } else {
                b.iconst_0();
            }
            b.invokestatic(TRACING, "exit", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_long,
                    ConstantDescs.CD_long, ConstantDescs.CD_boolean));
            b.labelBinding(skip);
        }

        /** The parameter's value on the stack, made the {@code long} its row records. */
        private static void widen(CodeBuilder b, ClassDesc p, String kind) {
            switch (p.descriptorString()) {
                case "Z", "B", "C", "S", "I" -> b.i2l();
                case "J" -> { }
                case "F" -> {
                    b.invokestatic(ClassDesc.of("java.lang.Float"), "floatToRawIntBits",
                            MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_float));
                    b.i2l();
                }
                case "D" -> b.invokestatic(ClassDesc.of("java.lang.Double"), "doubleToRawLongBits",
                        MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_double));
                default -> b.invokestatic(TRACING, kind.startsWith("enum:") ? "ordinal" : "length",
                        MethodTypeDesc.of(ConstantDescs.CD_long,
                                kind.startsWith("enum:") ? CD_ENUM : ConstantDescs.CD_String));
            }
        }

        private int paramIndex(String name) {
            for (int i = 0; i < site.paramNames().size(); i++) {
                if (site.paramNames().get(i).equals(name)) {
                    return i;
                }
            }
            throw new IllegalStateException("checked already: " + name);
        }

        private int slotOf(int index) {
            int slot = isStatic ? 0 : 1;
            for (int i = 0; i < index; i++) {
                slot += TypeKind.from(type.parameterType(i)).slotSize();
            }
            return slot;
        }
    }

    // --- the table --------------------------------------------------------------------------------------------

    /** {@code <package of the first site>.TraceSites_<module>}: one per module, in a package the module owns. */
    static String tableClassName(List<Found> sites, String module) {
        String owner = sites.get(0).owner();
        int dot = owner.lastIndexOf('.');
        String pkg = dot < 0 ? "" : owner.substring(0, dot + 1);
        String suffix = module.replaceAll("[^A-Za-z0-9]+", "_");
        return pkg + "TraceSites_" + (suffix.isEmpty() ? "module" : suffix);
    }

    /** The largest piece of the table one constant holds: a class file's strings are at most 65535 bytes. */
    private static final int CHUNK = 16_000;

    private static void writeTable(ClassFile cf, Path classes, String name, List<Found> sites, int major, int minor)
            throws IOException {
        StringBuilder table = new StringBuilder();
        for (Found s : sites) {
            table.append(s.tableLine()).append('\n');
        }
        String text = table.toString();
        ClassDesc self = ClassDesc.of(name);
        byte[] bytes = cf.build(self, clb -> {
            clb.withVersion(major, minor);
            clb.withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL, AccessFlag.SUPER);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.withInterfaceSymbols(TRACE_SITES);
            clb.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, cb -> {
                cb.aload(0);
                cb.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
                cb.return_();
            });
            clb.withMethodBody("table", MethodTypeDesc.of(ConstantDescs.CD_String), ClassFile.ACC_PUBLIC, cb -> {
                // Characters, not bytes, but a site's line is ASCII but for its names, and a chunk this size is
                // far under the limit even if every character took three bytes.
                cb.ldc(text.substring(0, Math.min(CHUNK, text.length())));
                for (int at = CHUNK; at < text.length(); at += CHUNK) {
                    cb.ldc(text.substring(at, Math.min(at + CHUNK, text.length())));
                    cb.invokevirtual(ConstantDescs.CD_String, "concat",
                            MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String));
                }
                cb.areturn();
            });
        });
        Path out = classes.resolve(name.replace('.', '/') + ".class");
        Files.createDirectories(out.getParent());
        Files.write(out, bytes);

        Path service = classes.resolve(SERVICE);
        Files.createDirectories(service.getParent());
        Set<String> lines = new LinkedHashSet<>();
        if (Files.exists(service)) {
            lines.addAll(Files.readAllLines(service, StandardCharsets.UTF_8));
        }
        lines.removeIf(String::isBlank);
        lines.add(name);
        Files.write(service, lines, StandardCharsets.UTF_8);
    }
}
