package com.brendan.holograms;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Repairs text that was meant to be UTF-8 but arrived one byte per character.
 * <p>
 * OpenComputers hands Lua strings to Java as bytes and its argument adapter decodes them with the
 * platform charset. On Windows that is cp1252, so a box-drawing character encoded as {@code E2 94
 * 82} reaches the projector as {@code "â”‚"} and would be painted as three cells. Mapping the
 * string back through cp1252 recovers those bytes; decoding them as UTF-8 restores the character.
 * <p>
 * The repair only applies when every character maps back to a cp1252 byte and those bytes are
 * valid UTF-8, so ordinary text is untouched: plain ASCII round-trips unchanged, and genuine
 * accented text such as {@code "café"} is not valid UTF-8 and is returned as-is. Text that already
 * contains real Unicode (a box character still present, for example) is left alone as well.
 */
public final class Utf8Text {
    private Utf8Text() {
    }

    /** Returns the UTF-8 reading of a byte-mapped string, or the input when that reading is not exact. */
    public static String repairByteMapped(String value) {
        if (value == null || value.isEmpty()) return value;
        byte[] bytes = new byte[value.length()];
        boolean hasHighByte = false;
        for (int index = 0; index < value.length(); index++) {
            int mapped = byteFor(value.charAt(index));
            if (mapped < 0) return value;
            bytes[index] = (byte) mapped;
            if (mapped >= 0x80) hasHighByte = true;
        }
        if (!hasHighByte) return value;
        try {
            String decoded = strictUtf8(bytes);
            return decoded.equals(value) ? value : decoded;
        } catch (CharacterCodingException e) {
            // Not a UTF-8 byte sequence: the text was already in a single-byte charset.
            return value;
        }
    }

    /** The cp1252 byte for a character, or -1 when the character is outside that charset. */
    private static int byteFor(char character) {
        if (character < 0x80 || (character >= 0x80 && character <= 0xFF)) return character;
        return switch (character) {
            case '\u20AC' -> 0x80; // €
            case '\u201A' -> 0x82; // ‚
            case '\u0192' -> 0x83; // ƒ
            case '\u201E' -> 0x84; // „
            case '\u2026' -> 0x85; // …
            case '\u2020' -> 0x86; // †
            case '\u2021' -> 0x87; // ‡
            case '\u02C6' -> 0x88; // ˆ
            case '\u2030' -> 0x89; // ‰
            case '\u0160' -> 0x8A; // Š
            case '\u2039' -> 0x8B; // ‹
            case '\u0152' -> 0x8C; // Œ
            case '\u017D' -> 0x8E; // Ž
            case '\u2018' -> 0x91; // ‘
            case '\u2019' -> 0x92; // ’
            case '\u201C' -> 0x93; // “
            case '\u201D' -> 0x94; // ”
            case '\u2022' -> 0x95; // •
            case '\u2013' -> 0x96; // –
            case '\u2014' -> 0x97; // —
            case '\u02DC' -> 0x98; // ˜
            case '\u2122' -> 0x99; // ™
            case '\u0161' -> 0x9A; // š
            case '\u203A' -> 0x9B; // ›
            case '\u0153' -> 0x9C; // œ
            case '\u017E' -> 0x9E; // ž
            case '\u0178' -> 0x9F; // Ÿ
            default -> -1;
        };
    }

    private static String strictUtf8(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }
}
