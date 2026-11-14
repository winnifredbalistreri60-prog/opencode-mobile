package ru.dredd20.opencode.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportPolicyTest {
    @Test
    fun phonePortraitIsMobileWebLayout() {
        assertFalse(ViewportPolicy.useDesktopWebLayout(412))
        assertEquals(ViewportPolicy.WidthClass.Compact, ViewportPolicy.widthClass(412))
    }

    @Test
    fun tabletAndLandscapeUseDesktopWebLayout() {
        assertTrue(ViewportPolicy.useDesktopWebLayout(768))
        assertTrue(ViewportPolicy.useDesktopWebLayout(1280))
        assertEquals(ViewportPolicy.WidthClass.Expanded, ViewportPolicy.widthClass(1280))
        assertEquals(ViewportPolicy.WidthClass.Medium, ViewportPolicy.widthClass(700))
        assertFalse(ViewportPolicy.useDesktopWebLayout(767))
    }
}
