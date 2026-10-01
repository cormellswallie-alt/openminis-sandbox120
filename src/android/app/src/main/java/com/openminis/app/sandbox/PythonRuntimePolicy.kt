package com.openminis.app.sandbox

/** Environment defaults only: no interpreter, worker, or bytecode cache lifecycle. */
internal object PythonRuntimePolicy {
    fun forCommand(
        base: Map<String, String>,
        userValues: Map<String, String>,
        streaming: Boolean,
    ): Map<String, String> {
        if (!streaming) return userValues
        val resolved = base.toMutableMap().also { it.putAll(userValues) }
        applyDefaults(resolved)
        // Return only the per-command overrides. The base is applied by the shell.
        return userValues.toMutableMap().also { overrides ->
            for (key in listOf("PYTHONUNBUFFERED", "PYTHONDONTWRITEBYTECODE")) {
                if (!base.containsKey(key) && !overrides.containsKey(key)) overrides[key] = resolved.getValue(key)
            }
        }
    }

    fun applyDefaults(env: MutableMap<String, String>) {
        // containsKey preserves explicit empty values as well as user settings.
        if (!env.containsKey("PYTHONUNBUFFERED")) env["PYTHONUNBUFFERED"] = "1"
        if (!env.containsKey("PYTHONDONTWRITEBYTECODE")) env["PYTHONDONTWRITEBYTECODE"] = "1"
    }
}
