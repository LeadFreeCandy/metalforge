package io.github.leadfreecandy.metalforge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optimization switches. Each optimization lives in its own mixin package (io.github.leadfreecandy.metalforge.mixin.&lt;name&gt;) and is applied
 * only if enabled. Set at launch with -Dmetalforge.opts, e.g. "all=off", "all=off,gpu_query=on", "particles=off".
 * Default: everything on. The test harness runs the same build with all=off (baseline) and the candidate set.
 */
public final class Options {
    /** Known optimizations: name -> description. Add an entry when adding a mixin package. */
    public static final Map<String, String> KNOWN = new LinkedHashMap<>();

    private static Map<String, Boolean> overrides;

    private Options() {}

    private static synchronized Map<String, Boolean> overrides() {
        if (overrides == null) {
            overrides = new LinkedHashMap<>();
            String spec = System.getProperty("metalforge.opts", "").trim();
            for (String part : spec.split(",")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && !kv[0].isBlank()) overrides.put(kv[0].trim(), !kv[1].trim().equalsIgnoreCase("off"));
            }
        }
        return overrides;
    }

    /** Options that are off unless named explicitly (none at the moment). */
    private static final java.util.Set<String> DEFAULT_OFF = java.util.Set.of();

    public static boolean enabled(String name) {
        Map<String, Boolean> o = overrides();
        if (o.containsKey(name)) return o.get(name);
        if (DEFAULT_OFF.contains(name)) return false;
        return o.getOrDefault("all", true);
    }

    public static String describe() {
        StringBuilder sb = new StringBuilder();
        for (String k : KNOWN.keySet()) sb.append(k).append('=').append(enabled(k) ? "on" : "off").append(' ');
        return sb.length() == 0 ? "(none yet)" : sb.toString().trim();
    }
}
