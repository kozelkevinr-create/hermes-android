package com.hermes.client.ui.plugins.achievements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementsModelsTest {
    private val body = """
        {"achievements":[
          {"id":"let_him_cook","name":"Let Him Cook","category":"Agent Autonomy","state":"unlocked",
           "tier":"Silver","next_tier":"Gold","progress_pct":32,"description":"d","criteria":"c","extra":1},
          {"id":"full_send","name":"Full Send","category":"Agent Autonomy","state":"discovered",
           "tier":null,"next_tier":null,"progress_pct":88},
          {"name":"no id is skipped"}
        ],
        "unlocked_count":46,"total_count":60,"secret_count":0,"is_stale":true,
        "scan_meta":{"status":{"state":"running"}}}
    """.trimIndent()

    @Test fun parsesSnapshot() {
        val s = parseAchievements(body)
        assertEquals(2, s.achievements.size)
        assertEquals(46, s.unlockedCount); assertEquals(60, s.totalCount)
        assertTrue(s.isStale); assertTrue(s.scanning)
        val a = s.achievements[0]
        assertTrue(a.unlocked); assertEquals("Silver", a.tier); assertEquals(32, a.progressPct)
        assertFalse(s.achievements[1].unlocked)
    }

    @Test fun missingFieldsFallBack() {
        val s = parseAchievements("""{"achievements":[{"id":"x"}]}""")
        assertEquals("x", s.achievements[0].name)
        assertEquals(1, s.totalCount); assertFalse(s.scanning)
    }
}
