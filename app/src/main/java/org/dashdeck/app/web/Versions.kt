package org.dashdeck.app.web

/** Compares dotted versions numerically ("0.13.10" > "0.13.9"); "-n" adds a build part. */
internal fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
    val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}
