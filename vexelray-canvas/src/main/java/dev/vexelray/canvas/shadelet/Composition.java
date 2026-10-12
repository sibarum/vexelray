package dev.vexelray.canvas.shadelet;

import dev.supirvast.vastir.core.Buffer;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.type.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import static dev.vexelray.ir.Ir.F32;
import static dev.vexelray.ir.Ir.I32;
import static dev.vexelray.ir.Ir.V2;
import static dev.vexelray.ir.Ir.V4;
import static dev.vexelray.ir.Ir.add;
import static dev.vexelray.ir.Ir.div;
import static dev.vexelray.ir.Ir.eq;
import static dev.vexelray.ir.Ir.f;
import static dev.vexelray.ir.Ir.i;
import static dev.vexelray.ir.Ir.lt;
import static dev.vexelray.ir.Ir.mul;
import static dev.vexelray.ir.Ir.sub;
import static dev.vexelray.ir.Ir.toInt;
import static dev.vexelray.ir.Ir.v2;
import static dev.vexelray.ir.Ir.v4;
import static dev.vexelray.ir.Ir.w;
import static dev.vexelray.ir.Ir.x;
import static dev.vexelray.ir.Ir.y;
import static dev.vexelray.ir.Ir.z;

/**
 * The shadelets one shader is built from, and the shader code that runs them for an element.
 *
 * <h2>The tables</h2>
 *
 * Everything an element's look says is in one float buffer, {@link #TABLES} (set 2, binding 0), read by the fragment
 * stage only. Indices in it are whole numbers held as floats, exact to 2^24. An element names its <b>record</b>:
 *
 * <pre>
 * element record at E:  [ look L | per-instance floats ... ]
 * look at L:            [ source id, shared offset, instance offset,
 *                         modifier count, (id, shared offset, instance offset) per modifier,
 *                         painter count,  (id, shared offset, instance offset) per painter ]
 * </pre>
 *
 * A shared offset is absolute in the buffer; an instance offset is relative to the element's record. Ids are each
 * shadelet's place among its role's in this composition, which the {@link Manifest} records by name.
 *
 * <h2>The code</h2>
 *
 * {@link #fragment} yields a function, {@code distanceAt(offset, aa, pixelsPerUnit, look, element)}, which runs the
 * look's source and modifiers by an if-chain on their ids; and the body that calls it at the fragment, then walks the
 * look's painters front to back, each into a layer composited <em>under</em> what is already there, premultiplied,
 * and ends in straight alpha. Every shadelet's code is in the shader once, however many looks use it.
 */
public final class Composition {

    public static final int TABLES_SET = 2;
    public static final int TABLES_BINDING = 0;
    public static final Buffer TABLES = new Buffer("shadeletTables", TABLES_SET, TABLES_BINDING, F32);

    /** A look's painters are capped, so a look is bounded work: more than this is a custom painter's job. */
    public static final int MAX_PAINTERS = 8;

    /**
     * Where the frame's element records start in {@link #TABLES}. Below it are the looks, the styles and the records
     * shared by every element of a look, written when a theme is ({@link LookTable}); above it, the records of elements
     * with values of their own, rewritten every frame by the canvas. A fixed line, so an element's offset can be
     * written into its vertices as it is drawn, while a look first drawn in the same frame is still being added below.
     */
    public static final int RECORDS_BASE = 1 << 16;

    /**
     * Floats of element records one frame may write: a million, four megabytes, beside a vertex buffer of twenty. A
     * host sizes {@link #TABLES} to {@link #TABLES_FLOATS}.
     */
    public static final int RECORDS_CAPACITY = 1 << 20;

    /** The whole of {@link #TABLES}: the looks below {@link #RECORDS_BASE}, a frame's records above it. */
    public static final int TABLES_FLOATS = RECORDS_BASE + RECORDS_CAPACITY;

    private final List<Shadelet.Source> sources;
    private final List<Shadelet.Modifier> modifiers;
    private final List<Shadelet.Painter> painters;

    private Composition(List<Shadelet.Source> sources, List<Shadelet.Modifier> modifiers,
                        List<Shadelet.Painter> painters) {
        this.sources = List.copyOf(sources);
        this.modifiers = List.copyOf(modifiers);
        this.painters = List.copyOf(painters);
    }

    /** The shadelets, each in its role, in the order given; names unique across all of them. */
    public static Composition of(List<? extends Shadelet> shadelets) {
        List<Shadelet.Source> sources = new ArrayList<>();
        List<Shadelet.Modifier> modifiers = new ArrayList<>();
        List<Shadelet.Painter> painters = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (Shadelet shadelet : shadelets) {
            if (!names.add(shadelet.name())) {
                throw new IllegalArgumentException("two shadelets are named '" + shadelet.name() + "'");
            }
            switch (shadelet) {
                case Shadelet.Source s -> sources.add(s);
                case Shadelet.Modifier m -> modifiers.add(m);
                case Shadelet.Painter p -> painters.add(p);
            }
        }
        if (sources.isEmpty() || painters.isEmpty()) {
            throw new IllegalArgumentException("a composition draws nothing without a source and a painter");
        }
        return new Composition(sources, modifiers, painters);
    }

    /** This composition's shadelets, then {@code more}: how an application adds its effects to the core set. */
    public Composition with(List<? extends Shadelet> more) {
        List<Shadelet> all = new ArrayList<>(sources);
        all.addAll(modifiers);
        all.addAll(painters);
        all.addAll(more);
        return of(all);
    }

    /** Every shadelet, sources then modifiers then painters, each role in the order given. */
    public List<Shadelet> shadelets() {
        return all();
    }

    /** What was compiled in: each shadelet's name, role, id and layout. */
    public Manifest manifest() {
        List<Manifest.Entry> entries = new ArrayList<>();
        for (int k = 0; k < sources.size(); k++) {
            entries.add(Manifest.Entry.of(sources.get(k), Manifest.Role.SOURCE, k));
        }
        for (int k = 0; k < modifiers.size(); k++) {
            entries.add(Manifest.Entry.of(modifiers.get(k), Manifest.Role.MODIFIER, k));
        }
        for (int k = 0; k < painters.size(); k++) {
            entries.add(Manifest.Entry.of(painters.get(k), Manifest.Role.PAINTER, k));
        }
        return new Manifest(entries);
    }

    /**
     * The fragment code, for a host.
     *
     * @param inputs  the host's {@link Fragment} for given {@code aa} and {@code pixelsPerUnit}: built from the
     *                host's varyings, which every function of the module can read
     * @param aa      the host's anti-aliasing half-width at this fragment
     * @param ppu     the host's pixels per canvas unit at this fragment
     * @param element the element's record offset, an {@code i32}: the host's flat varying
     * @param colour  where the body leaves the colour, a {@code vec4} in straight alpha, before the host's clip
     */
    public Composed fragment(BiFunction<Expr, Expr, Fragment> inputs, Expr aa, Expr ppu, Expr element,
                             LocalVar colour) {
        Function distanceAt = distanceAt(inputs);
        Fragment fragment = inputs.apply(aa, ppu);

        LocalVar look = new LocalVar("look", I32);
        LocalVar d = new LocalVar("d", V2);
        LocalVar acc = new LocalVar("acc", V4);
        LocalVar layer = new LocalVar("layer", V4);
        LocalVar base = new LocalVar("painterBase", I32);
        LocalVar count = new LocalVar("painterCount", I32);
        LocalVar index = new LocalVar("painterIndex", I32);
        LocalVar entry = new LocalVar("painterEntry", I32);
        LocalVar id = new LocalVar("painterId", I32);
        Expr readLook = new Expr.Read(look);
        Expr readEntry = new Expr.Read(entry);

        PaintContext context = new PaintContext(fragment, new Expr.Read(d), distanceAt, readLook, element);
        List<Region> branches = new ArrayList<>();
        for (int k = 0; k < painters.size(); k++) {
            Shadelet.Painter painter = painters.get(k);
            StyleView view = view(painter.style(), add(readEntry, i(1)), add(readEntry, i(2)), element);
            branches.add(painter.paint(context, view, layer));
        }

        Expr a = new Expr.Read(acc);
        Expr l = new Expr.Read(layer);
        Expr behind = sub(f(1.0), w(a));
        Expr under = v4(add(x(a), mul(behind, x(l))), add(y(a), mul(behind, y(l))),
                add(z(a), mul(behind, z(l))), add(w(a), mul(behind, w(l))));
        Expr cover = Expr.MathCall.max(w(a), f(1e-6));
        Expr straight = v4(div(x(a), cover), div(y(a), cover), div(z(a), cover), w(a));

        List<Statement> body = List.of(
                new Statement.DeclareVar(look, toInt(load(element))),
                new Statement.DeclareVar(d, new Expr.Call(distanceAt,
                        List.of(v2(0.0, 0.0), aa, ppu, readLook, element))),
                new Statement.DeclareVar(acc, v4(f(0.0), f(0.0), f(0.0), f(0.0))),
                new Statement.DeclareVar(layer, v4(f(0.0), f(0.0), f(0.0), f(0.0))),
                new Statement.DeclareVar(base, add(add(readLook, i(4)),
                        mul(toInt(load(add(readLook, i(3)))), i(3)))),
                new Statement.DeclareVar(count, toInt(load(new Expr.Read(base)))),
                new Statement.DeclareVar(index, i(0)),
                new Statement.DeclareVar(entry, i(0)),
                new Statement.DeclareVar(id, i(0)),
                new Statement.While(lt(new Expr.Read(index), new Expr.Read(count)), Region.of(
                        new Statement.Assign(entry, add(add(new Expr.Read(base), i(1)),
                                mul(new Expr.Read(index), i(3)))),
                        new Statement.Assign(id, toInt(load(readEntry))),
                        new Statement.Assign(layer, v4(f(0.0), f(0.0), f(0.0), f(0.0))),
                        dispatch(new Expr.Read(id), branches),
                        new Statement.Assign(acc, under),
                        new Statement.Assign(index, add(new Expr.Read(index), i(1))))),
                new Statement.DeclareVar(colour, straight));

        Map<String, Function> functions = new LinkedHashMap<>();
        for (Shadelet shadelet : all()) {
            for (Function helper : shadelet.helpers()) {
                functions.putIfAbsent(helper.name(), helper);
            }
        }
        List<Function> list = new ArrayList<>(functions.values());
        list.add(distanceAt);
        return new Composed(body, list);
    }

    /**
     * The fragment code and the functions it needs added to the module (helpers first, then {@code distanceAt}).
     *
     * @param body      statements declaring and assigning the colour local
     * @param functions add each to the module before the entry point
     */
    public record Composed(List<Statement> body, List<Function> functions) {
    }

    /** {@code vec2 distanceAt(vec2 offset, float aa, float ppu, int look, int element)}. */
    private Function distanceAt(BiFunction<Expr, Expr, Fragment> inputs) {
        Expr offset = new Expr.Param(0, V2);
        Fragment fragment = inputs.apply(new Expr.Param(1, F32), new Expr.Param(2, F32));
        Expr look = new Expr.Param(3, I32);
        Expr element = new Expr.Param(4, I32);

        LocalVar d = new LocalVar("distance", V2);
        LocalVar sourceId = new LocalVar("sourceId", I32);
        List<Region> sourceBranches = new ArrayList<>();
        for (int k = 0; k < sources.size(); k++) {
            Shadelet.Source source = sources.get(k);
            StyleView view = view(source.style(), add(look, i(1)), add(look, i(2)), element);
            sourceBranches.add(Region.of(new Statement.Assign(d, source.distance(fragment, offset, view))));
        }

        List<Statement> body = new ArrayList<>();
        body.add(new Statement.DeclareVar(d, v2(0.0, 0.0)));
        body.add(new Statement.DeclareVar(sourceId, toInt(load(look))));
        body.add(dispatch(new Expr.Read(sourceId), sourceBranches));
        if (!modifiers.isEmpty()) {
            LocalVar count = new LocalVar("modifierCount", I32);
            LocalVar index = new LocalVar("modifierIndex", I32);
            LocalVar entry = new LocalVar("modifierEntry", I32);
            LocalVar id = new LocalVar("modifierId", I32);
            Expr readEntry = new Expr.Read(entry);
            List<Region> modifierBranches = new ArrayList<>();
            for (int k = 0; k < modifiers.size(); k++) {
                Shadelet.Modifier modifier = modifiers.get(k);
                StyleView view = view(modifier.style(), add(readEntry, i(1)), add(readEntry, i(2)), element);
                modifierBranches.add(Region.of(
                        new Statement.Assign(d, modifier.modify(new Expr.Read(d), fragment, view))));
            }
            body.add(new Statement.DeclareVar(count, toInt(load(add(look, i(3))))));
            body.add(new Statement.DeclareVar(index, i(0)));
            body.add(new Statement.DeclareVar(entry, i(0)));
            body.add(new Statement.DeclareVar(id, i(0)));
            body.add(new Statement.While(lt(new Expr.Read(index), new Expr.Read(count)), Region.of(
                    new Statement.Assign(entry, add(add(look, i(4)), mul(new Expr.Read(index), i(3)))),
                    new Statement.Assign(id, toInt(load(readEntry))),
                    dispatch(new Expr.Read(id), modifierBranches),
                    new Statement.Assign(index, add(new Expr.Read(index), i(1))))));
        }
        body.add(new Statement.Return(new Expr.Read(d)));
        return new Function("distanceAt",
                new Type.FunctionType(V2, List.of(V2, F32, F32, I32, I32)), new Region(body));
    }

    /** {@code if (id == 0) {...} else if (id == 1) {...} ...}: the dispatch on one role's ids, branch k for id k. */
    private static Statement dispatch(Expr id, List<Region> branches) {
        Region rest = Region.of();
        for (int k = branches.size() - 1; k >= 1; k--) {
            rest = Region.of(new Statement.If(eq(id, i(k)), branches.get(k), rest));
        }
        return new Statement.If(eq(id, i(0)), branches.get(0), rest);
    }

    /** A style view whose shared base and instance offset are read from the table at the two given indices. */
    private static StyleView view(StyleLayout layout, Expr sharedAt, Expr instanceAt, Expr element) {
        return new StyleView(layout, TABLES, toInt(load(sharedAt)), add(element, toInt(load(instanceAt))));
    }

    private static Expr load(Expr index) {
        return new Expr.BufferLoad(TABLES, index);
    }

    private List<Shadelet> all() {
        List<Shadelet> all = new ArrayList<>(sources);
        all.addAll(modifiers);
        all.addAll(painters);
        return all;
    }
}
