package com.example.oreshik

import com.example.oreshik.agent.skills.SkillRepository
import com.example.oreshik.data.SkillEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the skill auto-match engine (SkillRepository), which decides which
 * skill instructions get injected into every agent request.
 *
 * Guards:
 *  - tokenization ignores short tokens and duplicates (keeps scoring deterministic),
 *  - a request only auto-injects skills with >= MIN_AUTO_MATCH_SCORE distinct hits,
 *  - the always-on index renders one stable line per enabled skill and hides disabled ones,
 *  - instruction bodies are capped so a huge skill cannot blow the context budget,
 *  - legacy skills (no instructions body) degrade gracefully to their description.
 */
class SkillMatchingTest {

    private fun skill(
        id: String,
        name: String,
        description: String,
        instructions: String = "",
        category: String = "general",
        enabled: Boolean = true,
    ) = SkillEntity(id, name, description, enabled, instructions, category, 0L, 0L)

    // ----- tokenize -----

    @Test
    fun `tokenize drops short tokens and deduplicates`() {
        // Duplicates collapse; stop words are dropped; "c0ding" and "coding" are DIFFERENT tokens
        // (no fuzzy leetspeak normalization — exact token identity only).
        assertEquals(
            listOf("c0ding", "workflow", "coding"),
            SkillRepository.tokenize("The C0DING workflow, workflow of Coding!"),
        )
    }

    @Test
    fun `tokenize keeps underscores and drops short tokens`() {
        // "v2" is only 2 chars — dropped by the min-length rule.
        assertEquals(listOf("gradle_exec"), SkillRepository.tokenize("gradle_exec v2"))
    }

    // ----- scoreSkill -----

    @Test
    fun `description hit alone does not reach auto-match threshold`() {
        val skill = skill("coding", "General coding", "Inspect, edit, test and verify source code.")
        // query: "code" hits description token "code"; only 1 distinct hit < 2
        assertTrue(SkillRepository.scoreSkill(skill, "what does this code do") < SkillRepository.MIN_AUTO_MATCH_SCORE)
    }

    @Test
    fun `multiple query tokens matching skill score above threshold`() {
        val skill = skill("android", "Android development", "Gradle, Android resources, Kotlin and APK verification.", category = "android")
        val score = SkillRepository.scoreSkill(skill, "my android build with gradle is broken")
        assertTrue("expected >= ${SkillRepository.MIN_AUTO_MATCH_SCORE}, got $score", score >= SkillRepository.MIN_AUTO_MATCH_SCORE)
    }

    @Test
    fun `full skill-name match earns the name bonus`() {
        val skill = skill("device", "Device automation", "Accessibility-driven device navigation.")
        val score = SkillRepository.scoreSkill(skill, "help me with device automation")
        assertTrue("name bonus should lift score: got $score", score >= SkillRepository.MIN_AUTO_MATCH_SCORE + 2)
    }

    @Test
    fun `unrelated query scores zero`() {
        val skill = skill("coding", "General coding", "Inspect, edit, test and verify source code.")
        assertEquals(0, SkillRepository.scoreSkill(skill, "banana spaceship xylophone"))
    }

    // ----- selectRelevant -----

    @Test
    fun `selectRelevant filters below-threshold and disabled skills`() {
        val relevant = skill("android", "Android development", "Gradle, Android resources, Kotlin and APK verification.")
        val irrelevant = skill("git", "Git workflow", "Inspect diffs, history and repository state before changes.")
        val disabledButRelevant = skill("android2", "Android testing", "android build verification", enabled = false)
        val picked = SkillRepository.selectRelevant(
            listOf(relevant, irrelevant, disabledButRelevant),
            "my android gradle build fails",
        )
        assertEquals(listOf(relevant.id), picked.map { it.id })
    }

    @Test
    fun `selectRelevant caps results at MAX_AUTO_MATCHES`() {
        val skills = (1..5).map { i ->
            skill("s$i", "Android task $i", "android gradle apk build resources", category = "android")
        }
        val picked = SkillRepository.selectRelevant(skills, "android gradle apk build")
        assertEquals(SkillRepository.MAX_AUTO_MATCHES, picked.size)
    }

    @Test
    fun `selectRelevant ranks higher score first`() {
        val strong = skill("device", "Device automation", "Accessibility-driven device navigation.")
        val weak = skill("coding", "General coding", "Inspect, edit, test and verify source code, accessibility of tests.")
        val picked = SkillRepository.selectRelevant(listOf(weak, strong), "device automation accessibility navigation")
        assertEquals("device", picked.first().id)
    }

    @Test
    fun `selectRelevant returns empty for blank query`() {
        val skill = skill("coding", "General coding", "Inspect, edit, test and verify source code.")
        assertTrue(SkillRepository.selectRelevant(listOf(skill), "   ").isEmpty())
    }

    // ----- renderIndex -----

    @Test
    fun `index lists only enabled skills with ids and hides disabled`() {
        val skills = listOf(
            skill("coding", "General coding", "Inspect, edit, test and verify source code."),
            skill("git", "Git workflow", "Inspect diffs, history and repository state before changes.", enabled = false),
        )
        val index = SkillRepository.renderIndex(skills)
        assertTrue(index.contains("[skill:coding]"))
        assertTrue(index.contains("General coding"))
        assertTrue(!index.contains("[skill:git]"))
        assertTrue(index.contains("skills.use"))
    }

    @Test
    fun `index is empty string when nothing enabled`() {
        val skills = listOf(skill("git", "Git workflow", "Inspect diffs.", enabled = false))
        assertEquals("", SkillRepository.renderIndex(skills))
    }

    @Test
    fun `index caps at MAX_INDEX_LINES with overflow hint`() {
        val many = ArrayList<SkillEntity>()
        for (i in 1..(SkillRepository.MAX_INDEX_LINES + 40)) {
            many.add(skill("s" + i, "Skill number " + i, "description for skill number " + i))
        }
        val index = SkillRepository.renderIndex(many)
        val lines = index.lines().filter { it.startsWith("[skill:") }
        assertEquals(SkillRepository.MAX_INDEX_LINES, lines.size)
        assertTrue(index.contains("more enabled skills not listed"))
        assertTrue(index.contains("skills.list"))
    }

    // ----- renderInstructions / renderMatchedBlock -----

    @Test
    fun `instructions are capped at MAX_INSTRUCTIONS_CHARS`() {
        val big = skill("big", "Big skill", "Large playbook", instructions = "x".repeat(SkillRepository.MAX_INSTRUCTIONS_CHARS + 5000))
        val rendered = SkillRepository.renderInstructions(big)
        assertTrue(rendered.contains("truncated at"))
        assertTrue(rendered.length < SkillRepository.MAX_INSTRUCTIONS_CHARS + 300)
    }

    @Test
    fun `legacy skill without instructions degrades to description`() {
        val legacy = skill("old", "Legacy skill", "Just a description line.")
        val rendered = SkillRepository.renderInstructions(legacy)
        assertTrue(rendered.contains("Legacy skill"))
        assertTrue(rendered.contains("Just a description line."))
    }

    @Test
    fun `matched block empty when nothing matched`() {
        assertEquals("", SkillRepository.renderMatchedBlock(emptyList()))
    }
}
