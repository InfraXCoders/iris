package com.infraxcoders.bmpcc.core

import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Blackmagic camera control over Bluetooth LE (Pocket 4K/6K/6K G2/6K Pro, Cinema Camera 6K, URSA…).
 *
 * The camera exposes one GATT service; commands use Blackmagic's SDI Camera Control Protocol:
 *   header  [destination, length, command id (0 = change configuration), reserved]
 *   command [category, parameter, data type, operation (0 = assign, 1 = offset)] + data
 * padded with zeros to a multiple of 4 bytes. `length` counts the command bytes without padding.
 * All numbers are little-endian; fixed16 is a signed 5.11 fixed-point number (value × 2048).
 *
 * Fixes vs the old Android app: fixed16 is data type 128 (not 12), shutter angle is 1.11 (not 1.7),
 * record is transport mode 2 (1 is Play), the LUT is 1.15 int8[2], and the outgoing/incoming
 * characteristics were swapped.
 */
object Bmd {
    const val SERVICE = "291D567A-6D75-11E6-8B77-86F30CA893D3"
    /** Phone → camera commands (write). */
    const val OUTGOING_CONTROL = "5DD3465F-1AEE-4299-8493-D2ECA2F8E1BB"
    /** Camera → phone state updates (notify). */
    const val INCOMING_CONTROL = "B864E140-76A0-416A-BF30-5876504537D9"
    const val TIMECODE = "6D8F2110-86F1-41BF-9AFB-451D87E976C8"
    /** Camera status flags (notify); write 0x01 to power the camera on. */
    const val CAMERA_STATUS = "7FE8691D-95DC-4FC5-8ABD-CA74E4B8B3FB"
    const val DEVICE_NAME = "FFAC0C52-C9FB-41A0-B063-CC76282EB89C"
    const val PROTOCOL_VERSION = "8F1FD018-B508-456F-8F82-3D392BEE2706"
    const val CCCD = "00002902-0000-1000-8000-00805F9B34FB"
    const val BROADCAST: Int = 0xFF

    enum class Type(val code: Int, val size: Int) {
        VOID(0, 0), INT8(1, 1), INT16(2, 2), INT32(3, 4), INT64(4, 8), STRING(5, 1), FIXED16(128, 2);

        companion object { fun of(code: Int): Type? = entries.firstOrNull { it.code == code } }
    }

    class Command(val category: Int, val parameter: Int, val type: Type, val operation: Int = 0, val data: ByteArray = ByteArray(0)) {
        override fun toString() = "Command($category.$parameter ${type.name} op=$operation ${data.joinToString(" ") { "%02X".format(it) }})"
        override fun equals(other: Any?) = other is Command && other.category == category && other.parameter == parameter &&
            other.type == type && other.operation == operation && other.data.contentEquals(data)
        override fun hashCode() = (category * 31 + parameter) * 31 + data.contentHashCode()

        fun int8(i: Int = 0): Int = data.getOrNull(i)?.toInt() ?: 0
        fun int16(i: Int = 0): Int = if (data.size >= i * 2 + 2) ((data[i * 2].toInt() and 0xFF) or (data[i * 2 + 1].toInt() shl 8)) else 0
        fun int32(i: Int = 0): Int = if (data.size >= i * 4 + 4)
            (data[i * 4].toInt() and 0xFF) or ((data[i * 4 + 1].toInt() and 0xFF) shl 8) or ((data[i * 4 + 2].toInt() and 0xFF) shl 16) or (data[i * 4 + 3].toInt() shl 24) else 0
        fun fixed16(i: Int = 0): Double = int16(i) / 2048.0
    }

    // ── Encoding ──

    fun encode(cmd: Command, destination: Int = BROADCAST): ByteArray {
        val length = 4 + cmd.data.size
        val padded = (length + 3) / 4 * 4
        val out = ByteArray(4 + padded)
        out[0] = destination.toByte(); out[1] = length.toByte(); out[2] = 0; out[3] = 0
        out[4] = cmd.category.toByte(); out[5] = cmd.parameter.toByte(); out[6] = cmd.type.code.toByte(); out[7] = cmd.operation.toByte()
        cmd.data.copyInto(out, 8)
        return out
    }

    private fun le16(vararg v: Int) = ByteArray(v.size * 2).also { b -> v.forEachIndexed { i, x -> b[i * 2] = x.toByte(); b[i * 2 + 1] = (x shr 8).toByte() } }
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    fun toFixed16(v: Double): Int = (v * 2048).roundToInt().coerceIn(-32768, 32767)
    private fun fx(vararg v: Double) = le16(*v.map { toFixed16(it) }.toIntArray())

    /** Record (transport mode 2) or stop (preview, mode 0). */
    fun record(on: Boolean) = Command(10, 1, Type.INT8, 0, byteArrayOf(if (on) 2 else 0, 0, 0, 0, 0))
    fun iso(iso: Int) = Command(1, 14, Type.INT32, 0, le32(iso))
    /** Shutter angle in degrees (sent ×100). */
    fun shutterAngle(degrees: Double) = Command(1, 11, Type.INT32, 0, le32((degrees * 100).roundToInt()))
    /** Shutter speed as 1/x seconds. */
    fun shutterSpeed(denominator: Int) = Command(1, 12, Type.INT32, 0, le32(denominator))
    fun whiteBalance(kelvin: Int, tint: Int = 0) = Command(1, 2, Type.INT16, 0, le16(kelvin, tint))
    fun autoWhiteBalance() = Command(1, 3, Type.VOID)
    /** Aperture as an f-number; sent as aperture value AV where f = sqrt(2^AV). */
    fun aperture(fNumber: Double) = Command(0, 2, Type.FIXED16, 0, fx(apertureValue(fNumber)))
    fun apertureNormalised(v: Double) = Command(0, 3, Type.FIXED16, 0, fx(v.coerceIn(0.0, 1.0)))
    fun autoAperture() = Command(0, 5, Type.VOID)
    /** Focus position 0 (near) … 1 (far), or an offset when [relative]. */
    fun focus(v: Double, relative: Boolean = false) = Command(0, 0, Type.FIXED16, if (relative) 1 else 0, fx(if (relative) v else v.coerceIn(0.0, 1.0)))
    fun autoFocus() = Command(0, 1, Type.VOID)
    fun zoomMm(mm: Int) = Command(0, 7, Type.INT16, 0, le16(mm))
    /** Built-in ND filter in stops (0, 2, 4, 6 on cameras that have one). */
    fun ndStops(stops: Double) = Command(1, 16, Type.FIXED16, 0, fx(stops))
    /** Monitor LUT shown on the camera's screen (1.15): which LUT and whether it is on. */
    fun displayLut(lut: Lut, enabled: Boolean = lut != Lut.NONE) =
        Command(1, 15, Type.INT8, 0, byteArrayOf(lut.code.toByte(), if (enabled) 1 else 0))
    /** Recording format (1.9): file and sensor frame rates, frame size and flags. Send back what the camera reported with a new rate. */
    fun recordingFormat(fileFps: Int, sensorFps: Int, width: Int, height: Int, flags: Int) =
        Command(1, 9, Type.INT16, 0, le16(fileFps, sensorFps, width, height, flags))

    enum class Lut(val code: Int, val label: String) {
        NONE(0, "Off"), CUSTOM(1, "Custom"), FILM_TO_VIDEO(2, "Film to Video"), FILM_TO_EXTENDED(3, "Film to Extended Video");
        companion object { fun of(code: Int): Lut = entries.firstOrNull { it.code == code } ?: NONE }
    }

    fun apertureValue(fNumber: Double): Double = 2 * ln(fNumber) / ln(2.0)
    fun fNumber(apertureValue: Double): Double = sqrt(2.0.pow(apertureValue))

    // ── Decoding ──

    /** Splits one notification into commands (a packet may carry several). */
    fun parse(bytes: ByteArray): List<Command> {
        val out = mutableListOf<Command>()
        var i = 0
        while (i + 8 <= bytes.size) {
            val length = bytes[i + 1].toInt() and 0xFF
            if (length < 4 || i + 4 + length > bytes.size) break
            val type = Type.of(bytes[i + 6].toInt() and 0xFF)
            if (type != null && (bytes[i + 2].toInt() and 0xFF) == 0) {
                out += Command(bytes[i + 4].toInt() and 0xFF, bytes[i + 5].toInt() and 0xFF, type, bytes[i + 7].toInt() and 0xFF,
                    bytes.copyOfRange(i + 8, i + 4 + length))
            }
            i += 4 + (length + 3) / 4 * 4
        }
        return out
    }

    /** What the camera reported. */
    sealed interface Update {
        data class Iso(val iso: Int) : Update
        data class ShutterAngle(val degrees: Double) : Update
        data class ShutterSpeed(val denominator: Int) : Update
        data class WhiteBalance(val kelvin: Int, val tint: Int) : Update
        data class Aperture(val fNumber: Double) : Update
        data class Focus(val position: Double) : Update
        data class Nd(val stops: Double) : Update
        data class Recording(val recording: Boolean) : Update
        data class Format(val fps: Int, val width: Int, val height: Int, val sensorFps: Int = fps, val flags: Int = 0) : Update
        data class DisplayLut(val lut: Lut, val enabled: Boolean) : Update
        /** Battery charge in percent, when the camera reports it (status 9.0: voltage mV, percent, flags). */
        data class Battery(val percent: Int) : Update
        data class Zoom(val mm: Int) : Update
    }

    fun decode(c: Command): Update? = when (c.category to c.parameter) {
        1 to 14 -> Update.Iso(c.int32())
        1 to 11 -> Update.ShutterAngle(c.int32() / 100.0)
        1 to 12 -> Update.ShutterSpeed(c.int32())
        1 to 2 -> Update.WhiteBalance(c.int16(0), c.int16(1))
        0 to 2 -> Update.Aperture(fNumber(c.fixed16()))
        0 to 0 -> Update.Focus(c.fixed16())
        1 to 16 -> Update.Nd(c.fixed16())
        10 to 1 -> Update.Recording(c.int8(0) == 2)
        1 to 9 -> Update.Format(c.int16(0), c.int16(2), c.int16(3), c.int16(1), c.int16(4))
        1 to 15 -> Update.DisplayLut(Lut.of(c.int8(0)), c.int8(1) != 0)
        9 to 0 -> if (c.type == Type.INT16 && c.data.size >= 4 && c.int16(1) in 0..100) Update.Battery(c.int16(1)) else null
        0 to 7 -> Update.Zoom(c.int16())
        else -> null
    }

    /** Timecode notification: little-endian BCD frames, seconds, minutes, hours (hours' top bit = drop frame). */
    fun timecode(bytes: ByteArray): String? {
        if (bytes.size < 4) return null
        fun bcd(b: Byte) = ((b.toInt() shr 4) and 0x0F) * 10 + (b.toInt() and 0x0F)
        val ff = bcd(bytes[0]); val ss = bcd(bytes[1]); val mm = bcd(bytes[2]); val hh = bcd((bytes[3].toInt() and 0x3F).toByte())
        return String.format(Locale.US, "%02d:%02d:%02d:%02d", hh, mm, ss, ff)
    }

    /** Camera status flags. */
    data class Status(val raw: Int) {
        val poweredOn get() = raw and 0x01 != 0
        val connected get() = raw and 0x02 != 0
        val paired get() = raw and 0x04 != 0
        val versionsVerified get() = raw and 0x08 != 0
        val initialPayloadReceived get() = raw and 0x10 != 0
        val ready get() = raw and 0x20 != 0
    }

    // ── Helpers to/from the app's shot settings ──

    fun ndPreset(stops: Double): String = when {
        stops < 0.5 -> "None"
        else -> {
            val s = stops.roundToInt()
            String.format(Locale.US, "ND %.1f (%d stop%s)", s * 0.3, s, if (s == 1) "" else "s")
        }
    }
}
