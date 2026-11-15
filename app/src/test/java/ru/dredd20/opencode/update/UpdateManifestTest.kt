package ru.dredd20.opencode.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class UpdateManifestTest {
    private val prefix = "https://github.com/winnifredbalistreri60-prog/opencode-mobile/releases/download/"
    private val sha = "a".repeat(64)
    private fun manifest(
        code: Any = 5,
        url: String = prefix + "v0.1.4/opencode-mobile-0.1.4.apk",
        sha256: String = sha,
        extra: String = "",
    ) = """{"versionCode":$code,"versionName":"0.1.4","url":"$url","sha256":"$sha256","size":1234,"minSdk":26,"notes":"fix"$extra}"""

    @Test fun parsesValidManifest() {
        val i = UpdateManifest.parse(manifest(), prefix)
        assertNotNull(i)
        i!!
        assertEquals(5, i.versionCode)
        assertEquals("0.1.4", i.versionName)
        assertEquals(sha, i.sha256)
        assertEquals(1234L, i.size)
        assertEquals(26, i.minSdk)
        assertEquals("fix", i.notes)
    }

    @Test fun uppercaseShaIsNormalized() {
        assertEquals(sha, UpdateManifest.parse(manifest(sha256 = "A".repeat(64)), prefix)!!.sha256)
    }

    @Test fun rejectsBadSha() {
        assertNull(UpdateManifest.parse(manifest(sha256 = "a".repeat(63)), prefix))
        assertNull(UpdateManifest.parse(manifest(sha256 = "g".repeat(64)), prefix))
        assertNull(UpdateManifest.parse(manifest(sha256 = ""), prefix))
    }

    @Test fun rejectsForeignOrTrickyUrls() {
        assertNull(UpdateManifest.parse(manifest(url = "https://evil.example/opencode-mobile.apk"), prefix))
        assertNull(UpdateManifest.parse(manifest(url = "http://github.com/winnifredbalistreri60-prog/opencode-mobile/releases/download/v1/a.apk"), prefix))
        assertNull(UpdateManifest.parse(manifest(url = "https://github.com/someone-else/opencode-mobile/releases/download/v1/a.apk"), prefix))
        assertNull(UpdateManifest.parse(manifest(url = prefix + "../../../evil/a.apk"), prefix))
        assertNull(UpdateManifest.parse(manifest(url = prefix + "v1/a.apk?x=1"), prefix))
        assertNull(UpdateManifest.parse(manifest(url = prefix + "v1/a.zip"), prefix))
    }

    @Test fun rejectsBadVersionOrJson() {
        assertNull(UpdateManifest.parse(manifest(code = 0), prefix))
        assertNull(UpdateManifest.parse(manifest(code = -3), prefix))
        assertNull(UpdateManifest.parse(manifest(code = "\"x\""), prefix))
        assertNull(UpdateManifest.parse("not json", prefix))
        assertNull(UpdateManifest.parse("{}", prefix))
        assertNull(UpdateManifest.parse(manifest(), ""))
    }

    @Test fun rejectsHugeSizeAndTruncatesNotes() {
        assertNull(UpdateManifest.parse(manifest().replace("\"size\":1234", "\"size\":${UpdateManifest.MAX_APK_BYTES + 1}"), prefix))
        val long = "x".repeat(10_000)
        val i = UpdateManifest.parse(manifest().replace("\"notes\":\"fix\"", "\"notes\":\"$long\""), prefix)!!
        assertEquals(UpdateManifest.MAX_NOTES, i.notes.length)
    }

    @Test fun missingOptionalFieldsUseDefaults() {
        val i = UpdateManifest.parse("""{"versionCode":7,"url":"${prefix}v/a.apk","sha256":"$sha"}""", prefix)!!
        assertEquals("7", i.versionName)
        assertEquals(-1L, i.size)
        assertEquals(0, i.minSdk)
    }

    @Test fun localTestServerPrefix() {
        val p = "http://10.0.2.2:8765/releases/download/"
        assertNotNull(UpdateManifest.parse(manifest(url = p + "v0.1.4/opencode-mobile-0.1.4.apk"), p))
        assertNull(UpdateManifest.parse(manifest(), p))
    }

    @Test fun versionComparisonByVersionCode() {
        val i = UpdateManifest.parse(manifest(code = 5), prefix)!!
        assertTrue(UpdateManifest.isNewer(i, 4))
        assertFalse(UpdateManifest.isNewer(i, 5))
        assertFalse(UpdateManifest.isNewer(i, 6))
        // versionName is ignored: "0.1.10" vs "0.1.9" only matters through versionCode
        val j = i.copy(versionName = "0.0.1")
        assertTrue(UpdateManifest.isNewer(j, 4))
    }

    @Test fun promptRespectsPostponedVersionOnlyForAutoCheck() {
        val i = UpdateManifest.parse(manifest(code = 5), prefix)!!
        assertTrue(UpdateManifest.shouldPrompt(i, 4, 0, auto = true))
        assertFalse(UpdateManifest.shouldPrompt(i, 4, 5, auto = true))
        assertTrue(UpdateManifest.shouldPrompt(i, 4, 5, auto = false))
        assertTrue(UpdateManifest.shouldPrompt(i.copy(versionCode = 6), 4, 5, auto = true))
        assertFalse(UpdateManifest.shouldPrompt(i, 5, 0, auto = false))
    }

    @Test fun autoCheckThrottleSixHours() {
        val h = 3600_000L
        val now = 100 * h
        assertTrue(UpdateManifest.shouldAutoCheck(true, now, 0L))
        assertFalse(UpdateManifest.shouldAutoCheck(true, now, now - 5 * h))
        assertTrue(UpdateManifest.shouldAutoCheck(true, now, now - 6 * h))
        assertTrue(UpdateManifest.shouldAutoCheck(true, now, now + h)) // clock went back
        assertFalse(UpdateManifest.shouldAutoCheck(false, now, 0L))
    }

    @Test fun sha256KnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", UpdateManifest.sha256Hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdateManifest.sha256Hex("abc".toByteArray()))
        val big = ByteArray(300_000) { (it % 251).toByte() }
        assertEquals(UpdateManifest.sha256Hex(big), UpdateManifest.sha256Hex(ByteArrayInputStream(big)))
    }

    @Test fun digestCompare() {
        assertTrue(UpdateManifest.sameDigest("ABC", "abc"))
        assertFalse(UpdateManifest.sameDigest("abc", "abd"))
        assertFalse(UpdateManifest.sameDigest("abc", "abcd"))
    }

    @Test fun signerMatch() {
        val mine = "65b96eb07abe416771b1a8c96184d87641d2e1cf51954ec340d40d34b32d2a90"
        assertTrue(UpdateManifest.signersMatch(setOf(mine), setOf(mine)))
        assertFalse(UpdateManifest.signersMatch(setOf("b".repeat(64)), setOf(mine)))
        assertFalse(UpdateManifest.signersMatch(emptySet(), setOf(mine)))
        assertFalse(UpdateManifest.signersMatch(setOf(mine), emptySet()))
    }

    @Test fun readyButtonFollowsLivePermission() {
        // MURF bug: button stayed "Open settings" after the permission was granted.
        assertEquals(UpdateManifest.ReadyAction.OPEN_SETTINGS, UpdateManifest.readyAction(false))
        assertEquals(UpdateManifest.ReadyAction.INSTALL, UpdateManifest.readyAction(true))
    }

    @Test fun apiFallbackFindsManifestAsset() {
        val api = """{"tag_name":"v0.1.4","draft":false,"prerelease":false,"assets":[
            {"name":"opencode-mobile-0.1.4.apk","browser_download_url":"${prefix}v0.1.4/opencode-mobile-0.1.4.apk"},
            {"name":"opencode-mobile-version.json","browser_download_url":"${prefix}v0.1.4/opencode-mobile-version.json"}]}"""
        assertEquals("${prefix}v0.1.4/opencode-mobile-version.json", UpdateManifest.manifestUrlFromApi(api, prefix))
    }

    @Test fun apiFallbackRejectsForeignOrMissingAsset() {
        val foreign = """{"assets":[{"name":"opencode-mobile-version.json","browser_download_url":"https://evil.example/opencode-mobile-version.json"}]}"""
        assertNull(UpdateManifest.manifestUrlFromApi(foreign, prefix))
        assertNull(UpdateManifest.manifestUrlFromApi("""{"assets":[]}""", prefix))
        assertNull(UpdateManifest.manifestUrlFromApi("""{"message":"Not Found"}""", prefix))
        val pre = """{"prerelease":true,"assets":[{"name":"opencode-mobile-version.json","browser_download_url":"${prefix}v1/opencode-mobile-version.json"}]}"""
        assertNull(UpdateManifest.manifestUrlFromApi(pre, prefix))
    }
}
