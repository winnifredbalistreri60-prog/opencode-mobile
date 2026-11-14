package ru.dredd20.opencode.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewSupportTest {
    @Test
    fun parseMajor() {
        assertEquals(124, WebViewSupport.majorVersion("124.0.6367.219"))
        assertEquals(126, WebViewSupport.majorVersion("126.0.0.0"))
        assertNull(WebViewSupport.majorVersion(null))
        assertNull(WebViewSupport.majorVersion(""))
    }

    @Test
    fun polyfillAllows124WhenDocumentStartWorks() {
        assertNull(WebViewSupport.blockingMessage(124, true, false))
        assertNotNull(WebViewSupport.advisoryMessage(124, false))
        assertNotNull(WebViewSupport.blockingMessage(124, false, false))
        assertNotNull(WebViewSupport.blockingMessage(70, true, false))
        assertNull(WebViewSupport.blockingMessage(126, false, false))
    }

    @Test
    fun polyfillDefinesParseAndCanParse() {
        val js = WebViewSupport.URL_PARSE_POLYFILL
        assertTrue(js.contains("URL.parse"))
        assertTrue(js.contains("URL.canParse"))
    }
}
