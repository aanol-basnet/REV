package app.rev.feature.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogTest {
    @Test
    fun `releases, sections and wrapped items are read`() {
        val text = """
            # Changelog

            Intro text that isn't a release.

            ## 0.2.0 — 2026-10-10

            ### New
            - Year in review, a **story** of
              your year.
            - `Equalizer`

            ### Fixed
            - Play next

            ## v0.1.0
            - First version
        """.trimIndent()

        assertEquals(
            listOf(
                Release(
                    "0.2.0",
                    "2026-10-10",
                    listOf(
                        ChangeSection("New", listOf("Year in review, a story of your year.", "Equalizer")),
                        ChangeSection("Fixed", listOf("Play next")),
                    ),
                ),
                Release("0.1.0", null, listOf(ChangeSection(null, listOf("First version")))),
            ),
            parseChangelog(text),
        )
    }

    @Test
    fun `the changelog has notes for the app's current version`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "CHANGELOG.md").exists() }
        val version = File(root, "android/REV/gradle.properties").readLines()
            .first { it.startsWith("rev.version=") }.substringAfter("=")
        val releases = parseChangelog(File(root, "CHANGELOG.md").readText())
        assertEquals("the newest changelog entry should be the app version", version, releases.first().version)
        assertTrue(releases.first().sections.isNotEmpty())
    }
}
