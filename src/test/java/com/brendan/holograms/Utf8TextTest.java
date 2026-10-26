package com.brendan.holograms;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Utf8TextTest {
    private static final Charset CP1252 = Charset.forName("windows-1252");
    /** The three UTF-8 bytes of a box-drawing vertical line, U+2502. */
    private static final byte[] BOX_VERTICAL = {(byte) 0xE2, (byte) 0x94, (byte) 0x82};

    @Test
    void recoversBoxDrawingCharactersDecodedAsCp1252() {
        String byteMapped = new String(BOX_VERTICAL, CP1252);
        assertEquals("\u00E2\u201D\u201A", byteMapped);
        assertEquals("\u2502", Utf8Text.repairByteMapped(byteMapped));
    }

    @Test
    void recoversTheSameBytesDecodedAsLatin1() {
        String byteMapped = new String(BOX_VERTICAL, Charset.forName("ISO-8859-1"));
        assertEquals("\u2502", Utf8Text.repairByteMapped(byteMapped));
    }

    @Test
    void leavesOverlappingUnicodeAlone() {
        // Already-decoded text must not be mapped back through cp1252; a box character is outside
        // that charset and is the normal output when OpenComputers decodes UTF-8 correctly.
        String unicode = "\u2502\u2500\u250C\u2510 \u00E9";
        assertEquals(unicode, Utf8Text.repairByteMapped(unicode));
    }

    @Test
    void leavesPlainAsciiAndSingleByteTextAlone() {
        assertEquals("echo hi", Utf8Text.repairByteMapped("echo hi"));
        // "café" as cp1252 bytes is E9, which is not valid UTF-8, so it stays as typed.
        assertEquals("caf\u00E9", Utf8Text.repairByteMapped("caf\u00E9"));
        assertEquals("", Utf8Text.repairByteMapped(""));
        assertEquals(null, Utf8Text.repairByteMapped(null));
    }

    @Test
    void leavesIncompleteSequencesAlone() {
        // A single box byte on its own is a malformed UTF-8 sequence and must not be corrupted.
        String truncated = new String(new byte[]{(byte) 0xE2}, CP1252);
        assertEquals(truncated, Utf8Text.repairByteMapped(truncated));
    }
}
