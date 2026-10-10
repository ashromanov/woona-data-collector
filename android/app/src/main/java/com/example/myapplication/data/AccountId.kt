package com.example.myapplication.data

fun normalizeAccountId(value: String): String {
    val trimmed = value.trim()
    require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}").matches(trimmed)) {
        "Идентификатор: 1–64 символа, латинские буквы, цифры, точка, дефис или подчёркивание"
    }
    return trimmed.lowercase(java.util.Locale.ROOT)
}
