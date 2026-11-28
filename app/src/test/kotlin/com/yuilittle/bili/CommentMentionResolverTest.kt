package com.yuilittle.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentMentionResolverTest {
    @Test fun findsNamesContainingSpacesAndPrefersTheLongestName() {
        val members = CommentMentionResolver.fromEntries(
            listOf("阿甲" to "11", "阿甲 同学" to "12")
        )

        val mentions = CommentMentionResolver.find("@阿甲 同学：@阿甲！", members)

        assertEquals(
            listOf(
                CommentMentionResolver.Mention(0, 6, "阿甲 同学", 12L),
                CommentMentionResolver.Mention(7, 10, "阿甲", 11L)
            ),
            mentions
        )
    }

    @Test fun acceptsTheLongestNameAtTheEndOfText() {
        val members = mapOf("一二三四五六七八九十" to 123L)

        assertEquals(
            listOf(CommentMentionResolver.Mention(0, 11, "一二三四五六七八九十", 123L)),
            CommentMentionResolver.find("@一二三四五六七八九十", members)
        )
    }

    @Test fun doesNotMatchANameInsideAWordOrAfterAnInvalidPrefix() {
        val members = mapOf("小明" to 20L)

        assertTrue(CommentMentionResolver.find("abc@小明def", members).isEmpty())
        assertTrue(CommentMentionResolver.find("@小明_", members).isEmpty())
    }

    @Test fun onlyPositiveNumericMidsAreAccepted() {
        val members = CommentMentionResolver.fromEntries(
            listOf(
                "有效" to "42",
                "零" to "0",
                "负" to "-1",
                "小数" to "1.5",
                "空" to "",
                "空白" to "   "
            )
        )

        assertEquals(mapOf("有效" to 42L), members)
    }

    @Test fun conflictingSameNamesAreRejectedWhileRepeatedSameMidRemainsUsable() {
        val members = CommentMentionResolver.fromEntries(
            listOf("同名" to "7", "同名" to "7", "同名" to "8", "同名" to "7", "唯一" to "9")
        )

        assertEquals(mapOf("唯一" to 9L), members)
        assertTrue(CommentMentionResolver.find("@同名 @唯一", members).none { it.name == "同名" })
        assertEquals(1, CommentMentionResolver.find("@同名 @唯一", members).size)
    }
}
