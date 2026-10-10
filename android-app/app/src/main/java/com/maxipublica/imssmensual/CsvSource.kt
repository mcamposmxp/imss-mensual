package com.maxipublica.imssmensual

/** Parser CSV tolerante (RFC 4180): BOM, comillas, comas y saltos de línea dentro de campos, ignora \r. */
object CsvSource {
    fun parse(text: String): List<Map<String, String>> {
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>(); val f = StringBuilder(); var q = false
        val t = text.removePrefix("﻿")
        var i = 0
        while (i < t.length) {
            val c = t[i]
            when {
                q && c == '"' && i + 1 < t.length && t[i + 1] == '"' -> { f.append('"'); i++ }
                c == '"' -> q = !q
                !q && c == ',' -> { row.add(f.toString()); f.clear() }
                !q && c == '\n' -> { row.add(f.toString()); f.clear(); rows.add(row); row = mutableListOf() }
                c == '\r' -> {}
                else -> f.append(c)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); rows.add(row) }
        val data = rows.filter { r -> r.any { it.isNotBlank() } }
        if (data.isEmpty()) return emptyList()
        val header = data[0].map { it.trim() }
        return data.drop(1).map { r -> header.indices.associate { header[it] to r.getOrElse(it) { "" }.trim() } }
    }

    fun transform(value: String, how: String?): String = when (how) {
        "upper" -> value.trim().uppercase()
        "lower" -> value.trim().lowercase()
        "digits" -> value.filter { it.isDigit() }
        else -> value.trim()
    }
}
