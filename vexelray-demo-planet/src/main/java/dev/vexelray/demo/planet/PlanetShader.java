package dev.vexelray.demo.planet;

import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.InterfaceVar;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.PushConstants;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.tools.Fullscreen;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.ir.Ir;
import dev.vexelray.shader.ClipDepth;
import dev.vexelray.shader.ComposedShader;

import java.util.ArrayList;
import java.util.List;

/**
 * The planet, its atmosphere and the stars behind it, as one fullscreen fragment stage in {@code core} IR. Units are
 * kilometres; the planet is centred on the origin of its own frame, which turns about {@code y} by the spin.
 *
 * <h2>The march</h2>
 *
 * <p>A pixel's ray starts where it enters the outer bound sphere, {@code radius + maxHeight}, nothing above which
 * can be ground, and steps by the field {@code |p| - R - max(h, 0)} over the terrain's {@link Terrain#lipschitz}
 * bound, so no step crosses the surface. The bound sphere is the shell of milestone one: exact, and free to
 * intersect. The quadtree of mesh shells replaces it later without changing the contract.
 *
 * <h2>The atmosphere</h2>
 *
 * <p>Single scattering, Rayleigh and Mie, integrated along the part of the view ray inside the atmosphere and in
 * front of whatever it hits, with the light's own optical depth taken toward the sun at every sample, and the
 * planet's shadow. The ground is lit by the sun through the same air, so dawn reddens the land as it does the sky.
 */
final class PlanetShader {

    /** Push constants: the eye, the camera's angles, the frame's aspect and the lens, the planet's spin. */
    static final String[] PUSH = {"eyeX", "eyeY", "eyeZ", "yaw", "pitch", "aspect", "focal", "spin"};

    static final int MARCH_STEPS = 220;

    /** Hit threshold as a fraction of distance: about a pixel at this lens and frame. */
    private static final double EPSILON = 0.0008;

    /** Toward the sun, in the world: it stays put while the planet turns under it. */
    static final double[] SUN = normalised(0.62, 0.22, -0.75);

    // Air, in kilometres: Earth's own coefficients, on a smaller world.
    private static final double ATMOSPHERE = 80;
    private static final double[] RAYLEIGH = {5.8e-3, 13.5e-3, 33.1e-3};
    private static final double RAYLEIGH_HEIGHT = 8.0;
    private static final double MIE = 21e-3;
    private static final double MIE_HEIGHT = 1.2;
    private static final double MIE_G = 0.76;
    private static final double SUN_POWER = 20.0;
    private static final int VIEW_SAMPLES = 14;
    private static final int LIGHT_SAMPLES = 6;

    /** How bright direct sun makes the ground, against {@link #SUN_POWER} for the air. */
    private static final double GROUND_LIGHT = 2.6;

    private final double radius;
    private final Terrain terrain;
    private final Function field;

    PlanetShader(double radius, Terrain terrain) {
        this.radius = radius;
        this.terrain = terrain;
        this.field = fieldFunction();
    }

    ComposedShader fragment(ClipDepth depth) {
        return ComposedShader.lower(ShaderStage.FRAGMENT, module(depth), "main");
    }

    static PushConstants block() {
        List<PushConstants.Member> members = new ArrayList<>();
        for (String name : PUSH) {
            members.add(new PushConstants.Member(name, Ir.F32));
        }
        return new PushConstants(members);
    }

    /** {@code float field(vec3 p)}: kilometres above the ground or the sea, negative below. */
    private Function fieldFunction() {
        Expr p = new Expr.Param(0, Ir.V3);
        LocalVar r = new LocalVar("r", Ir.F32);
        return new Function("field", new Type.FunctionType(Ir.F32, List.of(Ir.V3)), Region.of(
                new Statement.DeclareVar(r, Ir.length(p)),
                new Statement.Return(Ir.sub(Ir.sub(new Expr.Read(r), Ir.f(radius)),
                        Ir.max(new Expr.Call(terrain.height, List.of(Ir.scale(p, Ir.div(Ir.f(1.0),
                                new Expr.Read(r))))), Ir.f(0.0))))));
    }

    private CoreModule module(ClipDepth depth) {
        PushConstants pc = block();
        InterfaceVar vUv = InterfaceVar.input("vUv", Fullscreen.UV_LOCATION, Ir.V2);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, Ir.V4);
        Body b = new Body();

        // The pixel's ray, as SdfComposer.primaryRay builds it.
        Expr u = new Expr.VectorExtract(new Expr.InterfaceRead(vUv), 0);
        Expr v = new Expr.VectorExtract(new Expr.InterfaceRead(vUv), 1);
        Expr focal = pc.read(6);
        Expr sx = b.let("sx", Ir.mul(Ir.sub(Ir.mul(u, Ir.f(2.0)), Ir.f(1.0)), pc.read(5)));
        Expr sy = b.let("sy", Ir.sub(Ir.f(1.0), Ir.mul(v, Ir.f(2.0))));
        Expr cosForward = b.let("cosForward", Ir.div(focal, Ir.length(Ir.v3(sx, sy, focal))));
        Expr cp = b.let("cp", Expr.MathCall.cos(pc.read(4)));
        Expr sp = b.let("sp", Expr.MathCall.sin(pc.read(4)));
        Expr py = b.let("py", Ir.sub(Ir.mul(sy, cp), Ir.mul(focal, sp)));
        Expr pz = b.let("pz", Ir.add(Ir.mul(sy, sp), Ir.mul(focal, cp)));
        Expr cy = b.let("cy", Expr.MathCall.cos(pc.read(3)));
        Expr sny = b.let("sny", Expr.MathCall.sin(pc.read(3)));
        Expr worldDir = Expr.MathCall.normalize(Ir.v3(Ir.add(Ir.mul(sx, cy), Ir.mul(pz, sny)), py,
                Ir.sub(Ir.mul(pz, cy), Ir.mul(sx, sny))));

        // Into the planet's frame: turned back by the spin about y.
        Expr cs = b.let("cs", Expr.MathCall.cos(pc.read(7)));
        Expr sn = b.let("sn", Expr.MathCall.sin(pc.read(7)));
        Expr o = b.let("o", spin(Ir.v3(pc.read(0), pc.read(1), pc.read(2)), cs, sn));
        Expr d = b.let("d", spin(worldDir, cs, sn));
        Expr sun = b.let("sun", spin(Ir.v3(SUN[0], SUN[1], SUN[2]), cs, sn));

        // The bound sphere, and the march inside it.
        double outer = radius + terrain.maxHeight;
        Sphere bound = sphere(b, "bound", o, d, outer);
        LocalVar t = b.var("t", Ir.f(0.0));
        LocalVar tEnd = b.var("tEnd", Ir.f(0.0));
        b.add(new Statement.If(and(Ir.lt(Ir.f(0.0), bound.hit), Ir.lt(Ir.f(0.0), bound.far)), Region.of(
                new Statement.Assign(t, Ir.max(bound.near, Ir.f(0.0))),
                new Statement.Assign(tEnd, bound.far)), Region.of()));
        double step = 1.0 / terrain.lipschitz(radius);
        LocalVar dist = b.var("dist", Ir.mul(Ir.f(step), call(field, at(o, d, read(t)))));
        LocalVar steps = b.var("steps", Ir.f(0.0));
        Expr threshold = Ir.mul(Ir.f(EPSILON), read(t));
        b.add(new Statement.While(and(and(Ir.lt(read(steps), Ir.f(MARCH_STEPS)),
                Ir.lt(threshold, read(dist))), Ir.lt(read(t), read(tEnd))), Region.of(
                new Statement.Assign(t, Ir.add(read(t), read(dist))),
                new Statement.Assign(dist, Ir.mul(Ir.f(step), call(field, at(o, d, read(t))))),
                new Statement.Assign(steps, Ir.add(read(steps), Ir.f(1.0))))));
        // Landed: close enough, or out of steps still inside the band, which is a grazing ray at the ground.
        Expr landed = b.let("landed", Ir.mul(Ir.step(read(t), read(tEnd)),
                Ir.max(Ir.step(read(dist), threshold), Ir.step(Ir.f(MARCH_STEPS), read(steps)))));

        Sphere air = sphere(b, "air", o, d, radius + ATMOSPHERE);
        LocalVar colour = b.var("colour", Ir.v3(0, 0, 0));
        LocalVar depthT = b.var("depthT", Ir.f(-1.0));

        // The ground, or the sea.
        Body ground = b.branch();
        Expr p = ground.let("p", at(o, d, read(t)));
        Expr up = ground.let("up", Expr.MathCall.normalize(p));
        Expr h = ground.let("h", call(terrain.height, up));
        Expr n = ground.let("n", gradient(ground, p, Ir.max(Ir.mul(Ir.f(0.0012), read(t)), Ir.f(0.01))));
        Expr water = ground.let("water", Ir.step(h, Ir.f(0.0)));
        Expr lat = ground.let("lat", Ir.abs(Ir.y(up)));
        Expr albedo = ground.let("albedo", Ir.mix(land(ground, h, n, up, lat), sea(ground, h, lat),
                splat(water)));
        Expr normal = ground.let("normal", Ir.mix(n, up, splat(water)));
        Light sunAt = light(ground, "g", p, sun);
        Expr direct = ground.let("direct", Ir.scale(sunAt.transmittance,
                Ir.mul(Ir.max(Ir.dot(normal, sun), Ir.f(0.0)), Ir.f(GROUND_LIGHT))));
        Expr glint = ground.let("glint", Ir.scale(sunAt.transmittance, Ir.mul(water, Ir.mul(Ir.f(6.0),
                Expr.MathCall.pow(Ir.max(Ir.dot(Expr.MathCall.reflect(d, up), sun), Ir.f(0.0)), Ir.f(300.0))))));
        Expr lit = ground.let("lit", Ir.add(Ir.mul(albedo, Ir.add(direct, Ir.v3(0.02, 0.025, 0.035))), glint));
        Scatter viewAir = scatter(ground, "v", o, d, Ir.max(air.near, Ir.f(0.0)), read(t), sun);
        ground.add(new Statement.Assign(colour, Ir.add(Ir.mul(lit, viewAir.transmittance), viewAir.inscatter)));
        ground.add(new Statement.Assign(depthT, read(t)));

        // Space: stars and the sun, through whatever air the ray crosses.
        Body space = b.branch();
        Expr star = space.let("star", stars(space, worldDir));
        Expr disc = space.let("disc", Ir.mul(Ir.f(40.0), Expr.MathCall.pow(
                Ir.max(Ir.dot(d, sun), Ir.f(0.0)), Ir.f(60000.0))));
        Expr behind = space.let("behind", Ir.v3(Ir.add(star, disc), Ir.add(star, Ir.mul(disc, Ir.f(0.92))),
                Ir.add(star, Ir.mul(disc, Ir.f(0.8)))));
        LocalVar skyColour = space.var("skyColour", behind);
        Body throughAir = space.branch();
        Scatter skyAir = scatter(throughAir, "s", o, d, Ir.max(air.near, Ir.f(0.0)), air.far, sun);
        throughAir.add(new Statement.Assign(skyColour, Ir.add(Ir.mul(behind, skyAir.transmittance),
                skyAir.inscatter)));
        space.add(new Statement.If(and(Ir.lt(Ir.f(0.0), air.hit), Ir.lt(Ir.f(0.0), air.far)),
                throughAir.region(), Region.of()));
        space.add(new Statement.Assign(colour, read(skyColour)));

        b.add(new Statement.If(Ir.lt(Ir.f(0.5), landed), ground.region(), space.region()));
        // Filmic enough: 1 - exp(-x), which keeps the sun's glare and the night side both readable.
        Expr mapped = Ir.sub(Ir.v3(1, 1, 1), Expr.MathCall.exp(Ir.neg(Ir.scale(read(colour), Ir.f(1.1)))));
        b.add(new Statement.If(Ir.lt(Ir.f(0.0), read(depthT)),
                Region.of(depth.write(read(depthT), cosForward)), Region.of(depth.missed())));
        b.add(new Statement.InterfaceWrite(fragColor, new Expr.VectorConstruct(Ir.V4, List.of(
                Ir.x(mapped), Ir.y(mapped), Ir.z(mapped), Ir.f(1.0)))));
        b.add(new Statement.ReturnVoid());

        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), b.region());
        CoreModule module = new CoreModule();
        for (Function f : terrain.functions()) {
            module = module.addFunction(f);
        }
        return module.addFunction(field).addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
    }

    /**
     * Land colour: sand at the shore; forest, grassland or desert by moisture, drying with height; rock on slopes;
     * snow high and poleward. Moisture is a noise of its own, at the scale of climates rather than of hills.
     */
    private static Expr land(Body b, Expr h, Expr n, Expr up, Expr lat) {
        Expr slope = b.let("slope", Ir.sub(Ir.f(1.0), Ir.dot(n, up)));
        Expr wetness = b.let("wetness", Ir.clamp(Ir.add(Ir.f(0.5), Ir.mul(Ir.f(0.9), Ir.add(
                new Expr.Call(Terrain.NOISE, List.of(Ir.add(Ir.scale(up, Ir.f(2.3)), Ir.v3(41.0, 7.0, 13.0)))),
                Ir.mul(Ir.f(0.5), new Expr.Call(Terrain.NOISE, List.of(Ir.add(Ir.scale(up, Ir.f(7.1)),
                        Ir.v3(3.0, 59.0, 23.0)))))))), Ir.f(0.0), Ir.f(1.0)));
        Expr lowland = Ir.mix(Ir.v3(0.30, 0.25, 0.15), Ir.mix(Ir.v3(0.17, 0.22, 0.08), Ir.v3(0.05, 0.13, 0.04),
                splat(Expr.MathCall.smoothstep(Ir.f(0.55), Ir.f(0.85), wetness))),
                splat(Expr.MathCall.smoothstep(Ir.f(0.25), Ir.f(0.5), wetness)));
        Expr green = Ir.mix(lowland, Ir.v3(0.24, 0.21, 0.16),
                splat(Ir.clamp(Ir.div(h, Ir.f(5.0)), Ir.f(0.0), Ir.f(1.0))));
        Expr sandy = Ir.mix(Ir.v3(0.55, 0.50, 0.36), green,
                splat(Expr.MathCall.smoothstep(Ir.f(0.02), Ir.f(0.25), h)));
        Expr rocky = Ir.mix(sandy, Ir.v3(0.26, 0.24, 0.23),
                splat(Expr.MathCall.smoothstep(Ir.f(0.04), Ir.f(0.16), slope)));
        // The snow line falls from 6.5 km at the equator to the sea at the poles.
        Expr line = Ir.mul(Ir.f(6.5), Ir.sub(Ir.f(1.0), Ir.mul(lat, lat)));
        Expr snow = Expr.MathCall.smoothstep(line, Ir.add(line, Ir.f(0.8)), h);
        return Ir.mix(rocky, Ir.v3(0.92, 0.94, 0.97), splat(Ir.mul(snow, Ir.sub(Ir.f(1.0),
                Ir.mul(Ir.f(0.6), Expr.MathCall.smoothstep(Ir.f(0.2), Ir.f(0.35), slope))))));
    }

    /** Sea colour: shallows to deep by depth, and ice toward the poles. */
    private static Expr sea(Body b, Expr h, Expr lat) {
        Expr deep = b.let("deep", Ir.clamp(Ir.div(Ir.neg(h), Ir.f(2.0)), Ir.f(0.0), Ir.f(1.0)));
        Expr wet = Ir.mix(Ir.v3(0.04, 0.28, 0.32), Ir.v3(0.008, 0.03, 0.09), splat(deep));
        return Ir.mix(wet, Ir.v3(0.80, 0.86, 0.92), splat(Expr.MathCall.smoothstep(Ir.f(0.90), Ir.f(0.94), lat)));
    }

    /** Sparse stars: a lattice of directions, one in a few hundred lit. */
    private static Expr stars(Body b, Expr dir) {
        Expr cell = b.let("starCell", Expr.MathCall.floor(Ir.scale(dir, Ir.f(400.0))));
        Expr hash = b.let("starHash", new Expr.Call(Terrain.HASH, List.of(cell)));
        return Ir.mul(Expr.MathCall.smoothstep(Ir.f(0.9975), Ir.f(1.0), hash), Ir.f(3.0));
    }

    /** The field's gradient by the tetrahedral four-tap, at spacing {@code e}. */
    private Expr gradient(Body b, Expr p, Expr e) {
        Expr sum = null;
        double[][] taps = {{1, -1, -1}, {-1, -1, 1}, {-1, 1, -1}, {1, 1, 1}};
        for (double[] k : taps) {
            Expr tap = Ir.scale(Ir.v3(k[0], k[1], k[2]),
                    call(field, Ir.add(p, Ir.scale(Ir.v3(k[0], k[1], k[2]), e))));
            sum = sum == null ? tap : Ir.add(sum, tap);
        }
        return Expr.MathCall.normalize(sum);
    }

    // ---- the air --------------------------------------------------------------------------------------------

    private record Scatter(Expr inscatter, Expr transmittance) {
    }

    private record Light(Expr transmittance) {
    }

    /** The sun's light reaching {@code p}: through the air above it toward the sun, and none in the planet's shadow. */
    private Light light(Body b, String name, Expr p, Expr sun) {
        Optical od = towardSun(b, name, p, sun);
        return new Light(b.let(name + "SunT", Ir.scale(Expr.MathCall.exp(Ir.neg(extinction(od.rayleigh,
                od.mie))), od.lit)));
    }

    private record Optical(Expr rayleigh, Expr mie, Expr lit) {
    }

    /** Optical depth from {@code p} to the top of the air toward the sun, and whether the planet is in the way. */
    private Optical towardSun(Body b, String name, Expr p, Expr sun) {
        Sphere top = sphere(b, name + "Top", p, sun, radius + ATMOSPHERE);
        // The planet is in the way if the sun's ray meets it ahead. A sphere a little under sea level, so a point
        // on the sea is not in the shadow of its own surface.
        Sphere world = sphere(b, name + "World", p, sun, radius - 2.0);
        Expr lit = b.let(name + "Lit", Ir.sub(Ir.f(1.0), Ir.mul(world.hit, Ir.step(Ir.f(0.0), world.near))));
        Expr ds = b.let(name + "Ds", Ir.div(Ir.max(top.far, Ir.f(0.0)), Ir.f(LIGHT_SAMPLES)));
        LocalVar odR = b.var(name + "OdR", Ir.f(0.0));
        LocalVar odM = b.var(name + "OdM", Ir.f(0.0));
        LocalVar j = b.var(name + "J", Ir.f(0.0));
        Expr q = Ir.add(p, Ir.scale(sun, Ir.mul(Ir.add(read(j), Ir.f(0.5)), ds)));
        Expr height = Ir.sub(Ir.length(q), Ir.f(radius));
        b.add(new Statement.While(Ir.lt(read(j), Ir.f(LIGHT_SAMPLES)), Region.of(
                new Statement.Assign(odR, Ir.add(read(odR), Ir.mul(density(height, RAYLEIGH_HEIGHT), ds))),
                new Statement.Assign(odM, Ir.add(read(odM), Ir.mul(density(height, MIE_HEIGHT), ds))),
                new Statement.Assign(j, Ir.add(read(j), Ir.f(1.0))))));
        return new Optical(read(odR), read(odM), lit);
    }

    /**
     * Single scattering along {@code o + d t} for {@code t} in {@code [t0, t1]}: the light scattered toward the eye
     * there, and how much of what lies behind survives the trip.
     */
    private Scatter scatter(Body b, String name, Expr o, Expr d, Expr t0, Expr t1, Expr sun) {
        Expr from = b.let(name + "From", t0);
        Expr ds = b.let(name + "Ds", Ir.div(Ir.max(Ir.sub(t1, from), Ir.f(0.0)), Ir.f(VIEW_SAMPLES)));
        LocalVar odR = b.var(name + "OdR", Ir.f(0.0));
        LocalVar odM = b.var(name + "OdM", Ir.f(0.0));
        LocalVar sumR = b.var(name + "SumR", Ir.v3(0, 0, 0));
        LocalVar sumM = b.var(name + "SumM", Ir.v3(0, 0, 0));
        LocalVar i = b.var(name + "I", Ir.f(0.0));

        Body step = b.loopBody();
        Expr q = step.let(name + "Q", Ir.add(o, Ir.scale(d, Ir.add(from, Ir.mul(Ir.add(read(i), Ir.f(0.5)), ds)))));
        Expr height = step.let(name + "H", Ir.sub(Ir.length(q), Ir.f(radius)));
        Expr dr = step.let(name + "Dr", Ir.mul(density(height, RAYLEIGH_HEIGHT), ds));
        Expr dm = step.let(name + "Dm", Ir.mul(density(height, MIE_HEIGHT), ds));
        step.add(new Statement.Assign(odR, Ir.add(read(odR), dr)));
        step.add(new Statement.Assign(odM, Ir.add(read(odM), dm)));
        Optical toward = towardSun(step, name + "L", q, sun);
        Expr att = step.let(name + "Att", Ir.scale(Expr.MathCall.exp(Ir.neg(extinction(
                Ir.add(read(odR), toward.rayleigh), Ir.add(read(odM), toward.mie)))), toward.lit));
        step.add(new Statement.Assign(sumR, Ir.add(read(sumR), Ir.scale(att, dr))));
        step.add(new Statement.Assign(sumM, Ir.add(read(sumM), Ir.scale(att, dm))));
        step.add(new Statement.Assign(i, Ir.add(read(i), Ir.f(1.0))));
        b.add(new Statement.While(Ir.lt(read(i), Ir.f(VIEW_SAMPLES)), step.region()));

        Expr mu = b.let(name + "Mu", Ir.dot(d, sun));
        Expr phaseR = Ir.mul(Ir.f(3.0 / (16 * Math.PI)), Ir.add(Ir.f(1.0), Ir.mul(mu, mu)));
        double g = MIE_G;
        Expr phaseM = Ir.div(Ir.mul(Ir.f(3.0 / (8 * Math.PI) * (1 - g * g) / (2 + g * g)),
                Ir.add(Ir.f(1.0), Ir.mul(mu, mu))), Expr.MathCall.pow(Ir.sub(Ir.f(1 + g * g),
                Ir.mul(Ir.f(2 * g), mu)), Ir.f(1.5)));
        Expr inscatter = b.let(name + "In", Ir.scale(Ir.add(
                Ir.scale(Ir.mul(read(sumR), Ir.v3(RAYLEIGH[0], RAYLEIGH[1], RAYLEIGH[2])), phaseR),
                Ir.scale(Ir.scale(read(sumM), Ir.f(MIE)), phaseM)), Ir.f(SUN_POWER)));
        Expr transmittance = b.let(name + "T", Expr.MathCall.exp(Ir.neg(extinction(read(odR), read(odM)))));
        return new Scatter(inscatter, transmittance);
    }

    /** Extinction for optical depths of Rayleigh and Mie air: Mie absorbs a little as well as scattering. */
    private static Expr extinction(Expr odR, Expr odM) {
        return Ir.add(Ir.scale(Ir.v3(RAYLEIGH[0], RAYLEIGH[1], RAYLEIGH[2]), odR),
                splat(Ir.mul(Ir.f(MIE * 1.1), odM)));
    }

    private static Expr density(Expr height, double scale) {
        return Expr.MathCall.exp(Ir.div(Ir.neg(Ir.max(height, Ir.f(0.0))), Ir.f(scale)));
    }

    // ---- small things ---------------------------------------------------------------------------------------

    private record Sphere(Expr hit, Expr near, Expr far) {
    }

    /** Where the ray {@code o + d t} meets the sphere of {@code r} about the origin: hit is one or zero. */
    private static Sphere sphere(Body b, String name, Expr o, Expr d, double r) {
        Expr bb = b.let(name + "B", Ir.dot(o, d));
        Expr disc = b.let(name + "Disc", Ir.sub(Ir.mul(bb, bb), Ir.sub(Ir.dot(o, o), Ir.f(r * r))));
        Expr root = b.let(name + "Root", Ir.sqrt(Ir.max(disc, Ir.f(0.0))));
        return new Sphere(b.let(name + "Hit", Ir.step(Ir.f(0.0), disc)),
                b.let(name + "Near", Ir.sub(Ir.neg(bb), root)), b.let(name + "Far", Ir.add(Ir.neg(bb), root)));
    }

    /** {@code v} turned by {@code -spin} about {@code y}, given the spin's cosine and sine. */
    private static Expr spin(Expr v, Expr cs, Expr sn) {
        return Ir.v3(Ir.sub(Ir.mul(Ir.x(v), cs), Ir.mul(Ir.z(v), sn)), Ir.y(v),
                Ir.add(Ir.mul(Ir.x(v), sn), Ir.mul(Ir.z(v), cs)));
    }

    private Expr call(Function f, Expr arg) {
        return new Expr.Call(f, List.of(arg));
    }

    private static Expr at(Expr o, Expr d, Expr t) {
        return Ir.add(o, Ir.scale(d, t));
    }

    private static Expr read(LocalVar v) {
        return new Expr.Read(v);
    }

    private static Expr splat(Expr s) {
        return Ir.v3(s, s, s);
    }

    private static Expr and(Expr a, Expr b) {
        return new Expr.Binary(dev.supirvast.vastir.core.BinaryOp.LOGICAL_AND, a, b);
    }

    private static double[] normalised(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / length, y / length, z / length};
    }

    /**
     * Statements under construction, with locals named uniquely across the whole fragment: a branch or a loop body
     * is a Body of its own that shares the counter, so nothing is declared twice.
     */
    private static final class Body {

        private final List<Statement> statements = new ArrayList<>();
        private final int[] counter;

        Body() {
            this(new int[1]);
        }

        private Body(int[] counter) {
            this.counter = counter;
        }

        Body branch() {
            return new Body(counter);
        }

        Body loopBody() {
            return new Body(counter);
        }

        Expr let(String name, Expr value) {
            return new Expr.Read(var(name, value));
        }

        LocalVar var(String name, Expr value) {
            LocalVar local = new LocalVar(name + "_" + counter[0]++, value.type());
            statements.add(new Statement.DeclareVar(local, value));
            return local;
        }

        void add(Statement statement) {
            statements.add(statement);
        }

        Region region() {
            return new Region(statements);
        }
    }
}
