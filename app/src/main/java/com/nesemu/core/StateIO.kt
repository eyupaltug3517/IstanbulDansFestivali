package com.nesemu.core

import java.io.DataInputStream
import java.io.DataOutputStream

/** Components that can be written to / restored from a save state. */
interface Stateful {
    fun save(out: DataOutputStream)
    fun load(inp: DataInputStream)
}

fun DataOutputStream.writeBytes(arr: ByteArray) {
    writeInt(arr.size)
    write(arr)
}

fun DataInputStream.readBytesInto(arr: ByteArray) {
    val n = readInt()
    if (n != arr.size) throw IllegalStateException("Kayıt dosyası uyumsuz")
    readFully(arr)
}

fun DataOutputStream.writeInts(arr: IntArray) {
    writeInt(arr.size)
    for (v in arr) writeInt(v)
}

fun DataInputStream.readIntsInto(arr: IntArray) {
    val n = readInt()
    if (n != arr.size) throw IllegalStateException("Kayıt dosyası uyumsuz")
    for (i in arr.indices) arr[i] = readInt()
}
