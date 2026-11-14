package ru.dredd20.opencode.i18n

import java.util.Locale

/** Pick Russian or English from the device locale. UI strings stay in code so both languages ship together. */
fun tr(ru: String, en: String): String =
    if (Locale.getDefault().language.startsWith("ru")) ru else en
