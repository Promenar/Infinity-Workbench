package com.promenar.nexara.debug.linuxprobe

import kotlin.math.ceil

internal object ProbeCommands {
    const val COMPUTE = "s=0\nfor i in range(10_000_000): s+=i"

    fun quote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    fun createFiles(workspace: String, toybox: Boolean): String {
        val mkdir = if (toybox) "/system/bin/toybox mkdir" else "mkdir"
        return "b=${quote("$workspace/bench")}; $mkdir -p \"\$b\" || exit; i=0; " +
            "while [ \"\$i\" -lt 100 ]; do $mkdir -p \"\$b/d\$i\" || exit; j=0; " +
            "while [ \"\$j\" -lt 200 ]; do printf '%4096s' '' > \"\$b/d\$i/f\$j\" || exit; " +
            "j=\$((j+1)); done; i=\$((i+1)); done"
    }

    fun countFiles(workspace: String, toybox: Boolean): String {
        val prefix = if (toybox) "/system/bin/toybox " else ""
        return "n=\$(${prefix}find ${quote("$workspace/bench")} -type f | ${prefix}wc -l); echo \"\$n\"; [ \"\$n\" -eq 20000 ]"
    }

    fun tarFiles(workspace: String, toybox: Boolean): String {
        val prefix = if (toybox) "/system/bin/toybox " else ""
        return "${prefix}tar -cf ${quote("$workspace/bench.tar")} -C ${quote(workspace)} bench && " +
            "${prefix}mkdir -p ${quote("$workspace/bench-extracted")} && " +
            "${prefix}tar -xf ${quote("$workspace/bench.tar")} -C ${quote("$workspace/bench-extracted")}"
    }

    fun cleanup(workspace: String, toybox: Boolean): String =
        (if (toybox) "/system/bin/toybox " else "") + "rm -rf " +
            listOf("bench", "bench.tar", "bench-extracted").joinToString(" ") { quote("$workspace/$it") }

    fun statistics(samples: List<Double>): Pair<Double, Double> {
        require(samples.isNotEmpty())
        val sorted = samples.sorted()
        val median = (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
        return median to sorted[ceil(sorted.size * 0.9).toInt() - 1]
    }
}
