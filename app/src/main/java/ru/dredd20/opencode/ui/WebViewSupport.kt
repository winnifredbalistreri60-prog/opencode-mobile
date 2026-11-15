package ru.dredd20.opencode.ui

/**
 * URL.parse / URL.canParse landed in Chromium 126. Android System WebView 124
 * (typical AVD without Play) throws TypeError and the official OpenCode UI's
 * Connect button does nothing. This script is injected at document start.
 */
object WebViewSupport {
    const val MIN_RECOMMENDED_MAJOR = 126
    const val MIN_WITH_POLYFILL_MAJOR = 80

    const val URL_PARSE_POLYFILL = """
(function(){
  if (typeof URL === "undefined") return;
  if (typeof URL.parse !== "function") {
    URL.parse = function(url, base) {
      try { return new URL(url, base); } catch (e) { return null; }
    };
  }
  if (typeof URL.canParse !== "function") {
    URL.canParse = function(url, base) {
      try { new URL(url, base); return true; } catch (e) { return false; }
    };
  }
})();
"""

    fun majorVersion(versionName: String?): Int? {
        if (versionName.isNullOrBlank()) return null
        val first = versionName.trim().substringBefore('.').substringBefore(' ')
        return first.toIntOrNull()
    }

    /**
     * @return a user-facing reason the WebView is too old, or null if we can proceed
     * (native URL.parse, or a polyfill can be injected).
     */
    fun blockingMessage(major: Int?, canInjectDocumentStart: Boolean, russian: Boolean): String? {
        if (major == null) return null
        if (major >= MIN_RECOMMENDED_MAJOR) return null
        if (major >= MIN_WITH_POLYFILL_MAJOR && canInjectDocumentStart) return null
        return if (russian) {
            if (major >= MIN_WITH_POLYFILL_MAJOR) "Системный WebView $major не умеет подключать скрипт до загрузки страницы, поэтому заплатку для старых версий не поставить. Интерфейсу OpenCode нужен WebView 126 или новее. Обновите «Android System WebView» в магазине приложений."
            else "Системный WebView $major слишком старый для интерфейса OpenCode: нужен 126 или новее. Обновите «Android System WebView» в магазине приложений."
        } else {
            if (major >= MIN_WITH_POLYFILL_MAJOR) "System WebView $major cannot inject a script before the page loads, so the fix for older versions cannot be applied. The OpenCode UI needs WebView 126 or newer. Update “Android System WebView” from the app store."
            else "System WebView $major is too old for the OpenCode UI: it needs 126 or newer. Update “Android System WebView” from the app store."
        }
    }

    fun advisoryMessage(major: Int?, russian: Boolean): String? {
        if (major == null || major >= MIN_RECOMMENDED_MAJOR) return null
        if (major < MIN_WITH_POLYFILL_MAJOR) return blockingMessage(major, false, russian)
        return if (russian) {
            "WebView $major: включён полифил URL.parse. Для полной совместимости обновите Android System WebView до 126+."
        } else {
            "WebView $major: using a URL.parse polyfill. For full compatibility, update Android System WebView to 126+."
        }
    }
}
