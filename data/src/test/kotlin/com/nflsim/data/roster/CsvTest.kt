package com.nflsim.data.roster

import kotlin.test.Test
import kotlin.test.assertEquals

class CsvTest {

    @Test
    fun `plain rows parse`() {
        val rows = Csv.parse("a,b,c\n1,2,3")
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), rows)
    }

    @Test
    fun `quoted fields keep their commas`() {
        val rows = Csv.parse("name,pos\n\"Bollinger, Trey\",EDGE")
        assertEquals(listOf("Bollinger, Trey", "EDGE"), rows[1])
    }

    @Test
    fun `doubled quotes become one quote`() {
        val rows = Csv.parse("nick\n\"He said \"\"go\"\"\"")
        assertEquals("He said \"go\"", rows[1][0])
    }

    @Test
    fun `windows line endings are handled`() {
        val rows = Csv.parse("a,b\r\n1,2\r\n")
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), rows)
    }

    @Test
    fun `comments and blank lines are skipped`() {
        val rows = Csv.parse("# a note\n\na,b\n1,2\n")
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), rows)
    }

    @Test
    fun `a quote mark inside a comment does not swallow the file`() {
        // A comment is prose. Prose has apostrophes and quote marks in it.
        val text = "# Height accepts 74, 6-2, or 6'2\".\nname,pos\nSomebody,QB\nSomeone,WR"
        val rows = Csv.parse(text)
        assertEquals(3, rows.size)
        assertEquals(listOf("name", "pos"), rows[0])
        assertEquals(listOf("Someone", "WR"), rows[2])
    }

    @Test
    fun `writing escapes what needs escaping`() {
        val text = Csv.write(listOf(listOf("plain", "has,comma", "has\"quote")))
        assertEquals("plain,\"has,comma\",\"has\"\"quote\"", text)
    }

    @Test
    fun `write then parse round trips`() {
        val rows = listOf(
            listOf("name", "note"),
            listOf("Bollinger, Trey", "said \"hello\""),
            listOf("Plain", "nothing special"),
        )
        assertEquals(rows, Csv.parse(Csv.write(rows)))
    }
}
