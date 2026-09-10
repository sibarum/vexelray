package dev.vexelray.shader;

import dev.supirvast.vastir.core.BinaryOp;
import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.InterfaceVar;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.type.Type;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link CoreCheck} catches, and — the half that matters — that it catches the thing it was written for.
 *
 * <p>The first test is a reproduction, not an example. {@code mul(vec2, float)} is the expression that reached
 * NVIDIA's shader compiler and killed the JVM inside {@code nvgpucomp64.dll}; a checker that accepted it would
 * be a checker that cannot detect the one fault it exists to detect, which is worse than no checker at all
 * because it would be believed. Every other test here is a rule; that one is the reason.
 */
class CoreCheckTest {

    private static final Type.Float F32 = Type.float32();
    private static final Type.Vector V2 = new Type.Vector(F32, 2);
    private static final Type.Vector V4 = new Type.Vector(F32, 4);

    @Test
    void catchesTheMismatchThatCrashedTheDriver() {
        // vec2 * float. Constructs, reports vec2 as its own type, lowers to an OpFMul whose operands disagree.
        Expr bad = new Expr.Binary(BinaryOp.MUL, new Expr.Param(0, V2), new Expr.ConstFloat(F32, 2.0));

        List<String> faults = CoreCheck.faults(moduleReturning(V2, bad));

        assertEquals(1, faults.size(), () -> "expected exactly the operand mismatch, got " + faults);
        assertTrue(faults.get(0).contains("MUL"), faults.get(0));
        assertTrue(faults.get(0).contains("different types"), faults.get(0));
        // The message has to name the fix, because the reader hitting this has just written the expression and
        // does not yet know core has no implicit broadcast.
        assertTrue(faults.get(0).contains("broadcast"), faults.get(0));
    }

    @Test
    void acceptsTheCorrectedForm() {
        // What Ir.scale emits: the scalar broadcast to the vector's shape first.
        Expr two = new Expr.ConstFloat(F32, 2.0);
        Expr broadcast = new Expr.VectorConstruct(V2, List.of(two, two));
        Expr good = new Expr.Binary(BinaryOp.MUL, new Expr.Param(0, V2), broadcast);

        assertEquals(List.of(), CoreCheck.faults(moduleReturning(V2, good)));
    }

    @Test
    void catchesAVectorBuiltFromTheWrongNumberOfComponents() {
        Expr three = new Expr.VectorConstruct(V4,
                List.of(new Expr.ConstFloat(F32, 0), new Expr.ConstFloat(F32, 0), new Expr.ConstFloat(F32, 0)));

        List<String> faults = CoreCheck.faults(moduleReturning(V4, three));

        assertEquals(1, faults.size(), () -> faults.toString());
        assertTrue(faults.get(0).contains("3 components"), faults.get(0));
    }

    @Test
    void countsVectorComponentsByTheirArityNotByHowManyThereAre() {
        // A vec4 from a vec2 and two scalars is four components, not three — OpCompositeConstruct flattens.
        // Getting this wrong would reject correct IR, which is the failure mode that gets a checker switched off.
        Expr pair = new Expr.VectorConstruct(V2,
                List.of(new Expr.ConstFloat(F32, 0), new Expr.ConstFloat(F32, 0)));
        Expr four = new Expr.VectorConstruct(V4,
                List.of(pair, new Expr.ConstFloat(F32, 0), new Expr.ConstFloat(F32, 0)));

        assertEquals(List.of(), CoreCheck.faults(moduleReturning(V4, four)));
    }

    @Test
    void catchesAnExtractPastTheEndOfAVector() {
        Expr outOfRange = new Expr.VectorExtract(new Expr.Param(0, V2), 3);

        List<String> faults = CoreCheck.faults(moduleReturning(F32, outOfRange));

        assertEquals(1, faults.size(), () -> faults.toString());
        assertTrue(faults.get(0).contains("component 3"), faults.get(0));
    }

    @Test
    void catchesAReturnThatDoesNotMatchItsSignature() {
        List<String> faults = CoreCheck.faults(moduleReturning(F32, new Expr.Param(0, V2)));

        assertEquals(1, faults.size(), () -> faults.toString());
        assertTrue(faults.get(0).contains("return value"), faults.get(0));
    }

    @Test
    void catchesAWriteOfTheWrongTypeToAnInterfaceVariable() {
        InterfaceVar out = InterfaceVar.output("fragColor", 0, V4);
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()),
                Region.of(new Statement.InterfaceWrite(out, new Expr.Param(0, V2)),
                        new Statement.ReturnVoid()));

        List<String> faults = CoreCheck.faults(new CoreModule()
                .addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT)));

        assertEquals(1, faults.size(), () -> faults.toString());
        assertTrue(faults.get(0).contains("fragColor"), faults.get(0));
    }

    @Test
    void catchesAnAssignmentOfTheWrongTypeToALocal() {
        LocalVar local = new LocalVar("d", F32);
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()),
                Region.of(new Statement.Assign(local, new Expr.Param(0, V2)),
                        new Statement.ReturnVoid()));

        List<String> faults = CoreCheck.faults(new CoreModule()
                .addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT)));

        assertEquals(1, faults.size(), () -> faults.toString());
        assertTrue(faults.get(0).contains("'d'"), faults.get(0));
    }

    @Test
    void catchesAConditionThatIsNotABool() {
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()),
                Region.of(new Statement.If(new Expr.ConstFloat(F32, 1.0),
                                Region.of(new Statement.ReturnVoid()), null),
                        new Statement.ReturnVoid()));

        List<String> faults = CoreCheck.faults(new CoreModule()
                .addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT)));

        assertEquals(1, faults.size(), () -> faults.toString());
        assertTrue(faults.get(0).contains("rather than bool"), faults.get(0));
    }

    @Test
    void reportsEveryFaultRatherThanTheFirst() {
        Expr bad = new Expr.Binary(BinaryOp.MUL, new Expr.Param(0, V2), new Expr.ConstFloat(F32, 2.0));
        Expr worse = new Expr.Binary(BinaryOp.ADD, bad, new Expr.ConstFloat(F32, 1.0));

        // Both binaries disagree, so both are named: a composer that got one type wrong usually got the same
        // thing wrong in several places, and finding them one build at a time is the slow way.
        assertEquals(2, CoreCheck.faults(moduleReturning(V2, worse)).size());
    }

    @Test
    void lowerRefusesAModuleThatIsNotWellTyped() {
        Expr bad = new Expr.Binary(BinaryOp.MUL, new Expr.Param(0, V2), new Expr.ConstFloat(F32, 2.0));
        CoreModule module = moduleReturning(V2, bad);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ComposedShader.lower(ShaderStage.FRAGMENT, module, "main"));

        // The seam refuses rather than lowering, which is the whole point of putting the check there: every
        // runtime-composed shader in VexelRay passes through this one call.
        assertTrue(thrown.getMessage().contains("not well-typed"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("MUL"), thrown.getMessage());
    }

    /** A module whose one function returns {@code value}, for testing an expression in isolation. */
    private static CoreModule moduleReturning(Type returns, Expr value) {
        Function fn = new Function("subject", new Type.FunctionType(returns, List.of(returns)),
                Region.of(new Statement.Return(value)));
        return new CoreModule().addFunction(fn);
    }
}
