package dev.personal.airplayrx

import org.junit.Assert.assertEquals
import org.junit.Test

class TxtRecordTest {
    private fun record(vararg entries: String): ByteArray =
        entries.flatMap { e -> listOf(e.length.toByte()) + e.toByteArray().toList() }.toByteArray()

    @Test
    fun parsesKeyValuesInOrder() {
        val txt = record("deviceid=02:aa:bb:cc:dd:ee", "features=0x527FFEE6,0x0", "pw=false")
        assertEquals(
            listOf("deviceid" to "02:aa:bb:cc:dd:ee", "features" to "0x527FFEE6,0x0", "pw" to "false"),
            TxtRecord.parse(txt),
        )
    }

    @Test
    fun keyWithoutValueMapsToNull_andEmptyValueStaysEmpty() {
        assertEquals(listOf("flag" to null, "empty" to ""), TxtRecord.parse(record("flag", "empty=")))
    }

    @Test
    fun valueMayContainEquals() {
        assertEquals(listOf("k" to "a=b"), TxtRecord.parse(record("k=a=b")))
    }

    @Test
    fun ignoresTruncatedTrailingEntry() {
        val txt = record("a=1") + byteArrayOf(10, 'x'.code.toByte())
        assertEquals(listOf("a" to "1"), TxtRecord.parse(txt))
    }

    @Test
    fun handlesEntriesLongerThan127Bytes() {
        val long = "pk=" + "f".repeat(200)
        assertEquals(listOf("pk" to "f".repeat(200)), TxtRecord.parse(record(long)))
    }

    @Test
    fun emptyInputGivesNoEntries() {
        assertEquals(emptyList<Pair<String, String?>>(), TxtRecord.parse(ByteArray(0)))
    }
}
