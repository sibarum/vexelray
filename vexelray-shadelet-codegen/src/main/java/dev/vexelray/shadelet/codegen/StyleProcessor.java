package dev.vexelray.shadelet.codegen;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Generates {@code <Record>Layout} beside every {@code @Style} record: the record's {@code StyleLayout}, and the
 * writers that flatten a value of it into the style table ({@code shared}) and into an element's record
 * ({@code instance}). Hand-writing the two would mean keeping a layout and two float orders in step with the record
 * by eye; a field added to one and not the others shifts every parameter after it, and the shader reads the wrong
 * floats without a word.
 *
 * <p>It also generates {@code lerp}, which mixes two values of the style field by field: a float linearly, a colour
 * premultiplied (by {@code Color.lerp}). A look's states are separate values of one style, so this is what lets an
 * element animate from one to the other without anyone writing the mix, and getting a field wrong, by hand.
 *
 * <p>The annotations and {@code Color} are matched by name, not by class: the processor depends on nothing but the
 * JDK, so it loads on any processor path whether or not canvas is beside it. The generated source names every type
 * outright and imports nothing, so it cannot clash with a name the style's package declares.
 *
 * <p>A type that cannot become a layout is reported against itself and skipped; the rest of the round still
 * generates, so one bad style shows its own error rather than a wall of unresolved {@code Layout} references.
 */
@SupportedAnnotationTypes({StyleProcessor.STYLE, StyleProcessor.PER_INSTANCE})
public final class StyleProcessor extends AbstractProcessor {

    static final String STYLE = "dev.vexelray.canvas.shadelet.Style";
    static final String PER_INSTANCE = "dev.vexelray.canvas.shadelet.PerInstance";
    static final String COLOR = "dev.vexelray.canvas.Color";

    private static final String LAYOUT = "dev.vexelray.canvas.shadelet.StyleLayout";
    private static final String USE = "dev.vexelray.canvas.shadelet.Look.Use";
    private static final List<String> COLOR_CHANNELS = List.of("r", "g", "b", "a");

    private Messager messager;
    private Filer filer;
    private Elements elements;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        this.messager = env.getMessager();
        this.filer = env.getFiler();
        this.elements = env.getElementUtils();
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (TypeElement annotation : annotations) {
            if (!annotation.getQualifiedName().contentEquals(STYLE)) {
                continue;   // @PerInstance is read off the components of a style; alone it generates nothing
            }
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                try {
                    generate(element);
                } catch (AbortType e) {
                    // Diagnostic already emitted; skip this type and keep going.
                }
            }
        }
        return true;
    }

    // --- model ------------------------------------------------------------------

    /** One parameter: a record component, one float or a colour's four. */
    private record Field(String name, boolean color, boolean perInstance) {
        int width() {
            return color ? 4 : 1;
        }

        /** The expressions reading its floats off {@code s}, in table order. */
        List<String> reads() {
            String accessor = "s." + name + "()";
            if (!color) {
                return List.of(accessor);
            }
            List<String> reads = new ArrayList<>(4);
            for (String channel : COLOR_CHANNELS) {
                reads.add(accessor + "." + channel + "()");
            }
            return reads;
        }
    }

    private void generate(Element element) {
        String name = element.getSimpleName().toString();
        if (element.getKind() != ElementKind.RECORD) {
            fail(element, "@Style " + name + " is a " + element.getKind().toString().toLowerCase()
                    + "; a style is a record, whose components are its parameters in order");
        }
        TypeElement type = (TypeElement) element;
        if (type.getNestingKind().isNested()) {
            fail(type, "@Style " + name + " is nested; a style is a top-level record, so its "
                    + name + "Layout has a package to sit beside it in");
        }
        if (elements.getPackageOf(type).isUnnamed()) {
            fail(type, "@Style " + name + " is in the unnamed package; generated code cannot name it there");
        }
        if (!type.getTypeParameters().isEmpty()) {
            fail(type, "@Style " + name + " has type parameters; a style's parameters are floats and colours");
        }

        List<Field> fields = new ArrayList<>();
        for (RecordComponentElement component : type.getRecordComponents()) {
            fields.add(field(type, component));
        }

        String pkg = elements.getPackageOf(type).getQualifiedName().toString();
        String layoutSimple = name + "Layout";
        write(pkg + "." + layoutSimple, type, emit(pkg, type.getQualifiedName().toString(), layoutSimple, fields));
    }

    private Field field(TypeElement type, RecordComponentElement component) {
        String name = component.getSimpleName().toString();
        TypeMirror t = component.asType();
        boolean color;
        if (t.getKind() == TypeKind.FLOAT) {
            color = false;
        } else if (t.getKind() == TypeKind.DECLARED && ((TypeElement) ((DeclaredType) t).asElement())
                .getQualifiedName().contentEquals(COLOR)) {
            color = true;
        } else {
            throw fail(component, "@Style " + type.getSimpleName() + " component '" + name + "' is " + t
                    + "; a style component is a float or a " + COLOR);
        }
        return new Field(name, color, annotated(component, PER_INSTANCE));
    }

    private static boolean annotated(Element element, String annotation) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().contentEquals(annotation)) {
                return true;
            }
        }
        return false;
    }

    // --- emit -------------------------------------------------------------------

    private static String emit(String pkg, String style, String simple, List<Field> fields) {
        StringBuilder b = new StringBuilder(2048);
        b.append("package ").append(pkg).append(";\n\n");
        b.append("// Generated by the vexelray shadelet processor from @Style ").append(style)
         .append(". Do not edit.\n");
        b.append("public final class ").append(simple).append(" {\n\n");

        b.append("    public static final ").append(LAYOUT).append(" LAYOUT = ").append(LAYOUT).append(".of(");
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            b.append(i == 0 ? "\n            " : ",\n            ")
             .append(LAYOUT).append(f.perInstance() ? ".perInstance(\"" : ".shared(\"")
             .append(f.name()).append("\", ").append(f.width()).append(')');
        }
        b.append(");\n\n");

        b.append("    private ").append(simple).append("() {}\n\n");

        b.append("    public static float[] shared(").append(style).append(" s) {\n");
        b.append("        return new float[] {");
        appendReads(b, fields);
        b.append("};\n");
        b.append("    }\n\n");

        b.append("    public static void instance(").append(style).append(" s, float[] out, int at) {\n");
        int k = 0;
        for (Field f : fields) {
            if (!f.perInstance()) {
                continue;
            }
            for (String read : f.reads()) {
                b.append("        out[at + ").append(k++).append("] = ").append(read).append(";\n");
            }
        }
        b.append("    }\n\n");

        b.append("    public static ").append(style).append(" lerp(").append(style).append(" a, ").append(style)
         .append(" b, float t) {\n");
        b.append("        return new ").append(style).append('(');
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            String from = "a." + f.name() + "()";
            String to = "b." + f.name() + "()";
            b.append(i == 0 ? "\n                " : ",\n                ");
            if (f.color()) {
                b.append(COLOR).append(".lerp(").append(from).append(", ").append(to).append(", t)");
            } else {
                b.append(from).append(" + (").append(to).append(" - ").append(from).append(") * t");
            }
        }
        b.append(");\n");
        b.append("    }\n\n");

        b.append("    public static ").append(USE).append(" use(java.lang.String shadelet, ").append(style)
         .append(" s) {\n");
        b.append("        return ").append(USE).append(".of(shadelet, shared(s));\n");
        b.append("    }\n");

        b.append("}\n");
        return b.toString();
    }

    /** The shared fields' reads, comma separated. */
    private static void appendReads(StringBuilder b, List<Field> fields) {
        boolean first = true;
        for (Field f : fields) {
            if (f.perInstance()) {
                continue;
            }
            for (String read : f.reads()) {
                b.append(first ? "" : ", ").append(read);
                first = false;
            }
        }
    }

    private void write(String fqn, Element origin, String source) {
        try {
            JavaFileObject file = filer.createSourceFile(fqn, origin);
            try (Writer writer = file.openWriter()) {
                writer.write(source);
            }
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Failed to write " + fqn + ": " + e.getMessage(), origin);
        }
    }

    /** Reports an unrecoverable problem with one type against the element at fault, and aborts just that type. */
    private AbortType fail(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
        throw new AbortType();
    }

    private static final class AbortType extends RuntimeException {
        AbortType() {
            super(null, null, false, false);
        }
    }
}
