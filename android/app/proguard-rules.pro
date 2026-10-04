# DayCue R8 rules.
#
# kotlinx.serialization ships consumer rules inside its jar (META-INF/com.android.tools/r8).
# The rules below are a belt-and-braces copy for DayCue's own @Serializable types, which
# are persisted (config_current, engine_state JSON) and exchanged with the MCP relay, so a
# stripped serializer would corrupt data rather than just crash.

-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature

# Keep the Companion of every @Serializable class (holds serializer()).
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep serializer() on companion objects (default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep INSTANCE.serializer() of serializable objects (sealed ConfigOp/Event/Effect leaves).
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Generated $$serializer classes for DayCue types (domain + app).
-keep,includedescriptorclasses class app.daycue.**$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class app.daycue.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Security review L-9: no verbose/debug/info logging in release. Those calls carry item keys (`med:<id>`), place
# notes, relay sync reasons and config-derived text; logcat ends up in bug reports. R8 removes the calls together with
# their argument construction. Warnings and errors (`Log.w` / `Log.e`) stay: they carry exception types, not config.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
