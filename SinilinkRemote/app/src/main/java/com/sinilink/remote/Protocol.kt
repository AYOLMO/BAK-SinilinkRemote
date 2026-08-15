package com.sinilink.remote

/**
 * 欣易连 / Sinilink 功放 BLE 协议
 * 帧格式: [0x7E][总长度][载荷][校验和高][校验和低]
 * 校验和 = 校验和前所有字节求和, 对 65536 取模, 大端序
 */
object Protocol {
    const val SERVICE_UUID = "0000ae00-0000-1000-8000-00805f9b34fb"
    const val CHAR_RX = "0000ae04-0000-1000-8000-00805f9b34fb"
    const val CHAR_TX = "0000ae10-0000-1000-8000-00805f9b34fb"

    /** 音源命令 */
    const val CMD_SOURCE_AUX = 0x16
    const val CMD_SOURCE_BT = 0x14

    private const val CMD_STATUS = 0x1F
    private const val FRAME_SOF = 0x7E

    fun encodeFrame(payload: ByteArray): ByteArray {
        val totalLen = 2 + payload.size + 2
        val frame = ByteArray(totalLen)
        frame[0] = FRAME_SOF.toByte()
        frame[1] = totalLen.toByte()
        payload.copyInto(frame, 2)
        val checksum = frame.copyOfRange(0, totalLen - 2).sumOf { it.toInt() and 0xFF } % 65536
        frame[totalLen - 2] = (checksum shr 8).toByte()
        frame[totalLen - 1] = (checksum and 0xFF).toByte()
        return frame
    }

    fun statusRequest(): ByteArray = encodeFrame(byteArrayOf(CMD_STATUS.toByte()))

    fun sourceCommand(source: Int): ByteArray = encodeFrame(byteArrayOf(source.toByte()))

    /** 判断一帧是否为状态响应(用于连接成功验证) */
    fun isStatusFrame(frame: ByteArray): Boolean {
        if (frame.size < 5 || (frame[0].toInt() and 0xFF) != FRAME_SOF) return false
        val payload = frame.copyOfRange(2, frame.size - 2)
        return payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == CMD_STATUS
    }
}
