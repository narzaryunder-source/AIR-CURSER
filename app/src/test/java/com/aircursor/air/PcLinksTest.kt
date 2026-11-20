package com.aircursor.air

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PcLinksTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Claude Desktop's real menu entry (claude-desktop 5.11492.5, arm64 package). */
    private val claude = """
        [Desktop Entry]
        Name=Claude
        Exec=claude-desktop %U
        MimeType=x-scheme-handler/claude;application/vnd.anthropic.mcpb;application/vnd.anthropic.skill;
    """.trimIndent()

    private val thunderbird = "[Desktop Entry]\nName=Thunderbird\nMimeType=x-scheme-handler/mailto;message/rfc822;\n"

    private fun pc(vararg entries: Pair<String, String>): File {
        val root = tmp.newFolder()
        for ((name, text) in entries) File(root, "usr/share/applications/$name").apply { parentFile!!.mkdirs(); writeText(text) }
        return root
    }

    @Test
    fun claudeSignInLinkGoesBackToTheClaudeAppInThePc() {
        val root = pc("com.anthropic.Claude.desktop" to claude)
        assertTrue(PcLinks.forPc("claude://claude.ai/login?token=abc", root))
        assertTrue(PcLinks.forPc("CLAUDE://claude.ai/new", root))
    }

    @Test
    fun withoutThePcAppTheLinkStaysOnThePhone() {
        val root = pc("thunderbird.desktop" to thunderbird)
        assertFalse(PcLinks.forPc("claude://claude.ai/login?token=abc", root))
        assertFalse(PcLinks.forPc("claude://x", File(tmp.root, "no-pc-here")))
    }

    @Test
    fun phoneLinksStayOnThePhoneEvenWhenAPcAppCouldOpenThem() {
        val root = pc("thunderbird.desktop" to thunderbird, "upi.desktop" to "MimeType=x-scheme-handler/upi;\n")
        assertFalse(PcLinks.forPc("mailto:someone@example.com", root))
        assertFalse(PcLinks.forPc("upi://pay?pa=x", root))
        assertFalse(PcLinks.forPc("intent://scan/#Intent;scheme=zxing;end", root))
        assertFalse(PcLinks.forPc("https://claude.ai", root))
    }

    @Test
    fun entriesInTheAirCursorCopiesAndTheUsersFolderCount() {
        val root = tmp.newFolder()
        File(root, "usr/local/share/applications/cursor.desktop").apply { parentFile!!.mkdirs(); writeText("MimeType=x-scheme-handler/cursor;\n") }
        File(root, "root/.local/share/applications/zed.desktop").apply { parentFile!!.mkdirs(); writeText("MimeType=x-scheme-handler/zed\n") }
        assertTrue(PcLinks.forPc("cursor://auth/callback?code=1", root))
        assertTrue(PcLinks.forPc("zed://x", root))
    }

    @Test
    fun schemeParsing() {
        assertEquals("claude", PcLinks.scheme("claude://claude.ai"))
        assertEquals("vscode-insiders", PcLinks.scheme("vscode-insiders://x"))
        assertEquals(null, PcLinks.scheme("no scheme here"))
    }
}
