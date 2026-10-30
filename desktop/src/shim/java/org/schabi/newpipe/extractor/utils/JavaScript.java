package org.schabi.newpipe.extractor.utils;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

/**
 * NewPipe's runner for YouTube's player code (the signature and throttling
 * functions), on GraalJS instead of NewPipe's Rhino. GraalJS keeps up with
 * current JavaScript, which YouTube's player code moves to faster than Rhino
 * can parse it. Rhino, as NewPipe uses it, stays as the fallback should
 * GraalJS fail. The desktop build leaves NewPipe's own copy of this class
 * out of its jar (see desktop/build.gradle.kts).
 */
public final class JavaScript {
    private JavaScript() {
    }

    /** One engine for every run: its parsed code is shared, each run's state is not. */
    private static final Engine ENGINE = Engine.newBuilder("js")
            // No JIT on a stock JDK; the interpreter is plenty for a few short functions.
            .option("engine.WarnInterpreterOnly", "false")
            .build();

    private static Context context() {
        return Context.newBuilder("js").engine(ENGINE).allowAllAccess(false).build();
    }

    public static void compileOrThrow(final String function) {
        try (Context cx = context()) {
            cx.parse(Source.create("js", function));
            return;
        } catch (final RuntimeException graal) {
            // Rhino's verdict decides: it is what NewPipe expects here.
            rhinoCompile(function);
        }
    }

    public static String run(final String function, final String functionName, final String... parameters) {
        try (Context cx = context()) {
            cx.eval("js", function);
            final Value f = cx.getBindings("js").getMember(functionName);
            final Value result = f.execute((Object[]) parameters);
            return result.isString() ? result.asString() : result.toString();
        } catch (final RuntimeException graal) {
            return rhinoRun(function, functionName, parameters);
        }
    }

    private static void rhinoCompile(final String function) {
        try (org.mozilla.javascript.Context cx = org.mozilla.javascript.Context.enter()) {
            cx.setInterpretedMode(true);
            cx.compileString(function, "", 1, null);
        }
    }

    private static String rhinoRun(final String function, final String functionName, final String... parameters) {
        try (org.mozilla.javascript.Context cx = org.mozilla.javascript.Context.enter()) {
            cx.setInterpretedMode(true);
            final org.mozilla.javascript.ScriptableObject scope = cx.initSafeStandardObjects();
            cx.evaluateString(scope, function, functionName, 1, null);
            final org.mozilla.javascript.Function f = (org.mozilla.javascript.Function) scope.get(functionName, scope);
            return f.call(cx, scope, scope, parameters).toString();
        }
    }
}
