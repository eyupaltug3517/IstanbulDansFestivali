package com.nesemu.core

/**
 * A cheat code. Two formats are accepted:
 *  - Game Genie, 6 or 8 letters (e.g. "SXIOPO", "AEKPIAZA"): patches cartridge ROM reads.
 *  - RAM code "AAAA:VV" (hex, e.g. "075A:09"): writes VV to RAM address AAAA every frame.
 */
data class Cheat(val code: String, val address: Int, val value: Int, val compare: Int = -1, val ram: Boolean = false) {
    companion object {
        private const val LETTERS = "APZLGITYEOXUKSVN"
        private val RAM_RE = Regex("^([0-9A-F]{1,4}):([0-9A-F]{1,2})$")

        /** Returns null if [input] is not a valid code. */
        fun parse(input: String): Cheat? {
            val code = input.trim().uppercase().replace(" ", "").replace("-", "")
            RAM_RE.matchEntire(code)?.let { m ->
                val addr = m.groupValues[1].toInt(16)
                if (addr >= 0x0800 && addr !in 0x6000..0x7FFF) return null
                return Cheat(code, addr, m.groupValues[2].toInt(16), ram = true)
            }
            if (code.length != 6 && code.length != 8) return null
            val n = IntArray(code.length) { LETTERS.indexOf(code[it]).also { v -> if (v < 0) return null } }
            val address = 0x8000 or
                ((n[3] and 7) shl 12) or ((n[5] and 7) shl 8) or ((n[4] and 8) shl 8) or
                ((n[2] and 7) shl 4) or ((n[1] and 8) shl 4) or (n[4] and 7) or (n[3] and 8)
            return if (code.length == 6) {
                val value = ((n[1] and 7) shl 4) or ((n[0] and 8) shl 4) or (n[0] and 7) or (n[5] and 8)
                Cheat(code, address, value)
            } else {
                val value = ((n[1] and 7) shl 4) or ((n[0] and 8) shl 4) or (n[0] and 7) or (n[7] and 8)
                val compare = ((n[7] and 7) shl 4) or ((n[6] and 8) shl 4) or (n[6] and 7) or (n[5] and 8)
                Cheat(code, address, value, compare)
            }
        }
    }
}
