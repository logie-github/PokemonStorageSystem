package com.logie.packageexporter
import org.junit.Assert.*
import org.junit.Test
class SaveParserTest {
    @Test fun readsNumericKeysAndSparseBoxes() {
        val save = SaveParser("""return {
            player = { name = "RED", id = 1234, },
            party = { [1] = { species = "PIKACHU", level = 5, }, },
            boxes = { [14] = { [1] = { species = "CHIKORITA", }, }, [1] = {}, },
        }""").parse()
        assertEquals("RED", save.child("player")["name"])
        assertEquals("PIKACHU", save.child("party").numbered().single().second["species"])
        assertEquals(listOf(1, 14), save.child("boxes").numbered().map { it.first })
    }
    @Test fun readsLegacyBoxAndDecimalEscapes() {
        val save = SaveParser("""return { player = { name = "R\069D", }, party = {}, box = { [1] = { species = "MEW", }, }, }""").parse()
        assertEquals("RED", save.child("player")["name"])
        assertEquals("MEW", save.child("box").numbered().single().second["species"])
    }
    @Test fun rejectsCodeAndTrailingContent() {
        for (input in listOf("return os.execute()", "return {} os.execute()", "return { a = function() end }")) {
            assertTrue(runCatching { SaveParser(input).parse() }.isFailure)
        }
    }
}
