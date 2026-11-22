package network.clasher.clashercity.util;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class FormatUtilTest {

    @Test
    void validatesPlotAndAreaIdentifiers() {
        assertDoesNotThrow(() -> FormatUtil.validateId("A-01"));
        assertDoesNotThrow(() -> FormatUtil.validateId("ROAD_north_1"));
        assertThrows(IllegalArgumentException.class, () -> FormatUtil.validateId(""));
        assertThrows(IllegalArgumentException.class, () -> FormatUtil.validateId("bad id with spaces"));
        assertThrows(IllegalArgumentException.class, () -> FormatUtil.validateId("a".repeat(33)));
    }

    @Test
    void parsesLegacyAndMiniMessageCleanly() {
        String legacy = PlainTextComponentSerializer.plainText().serialize(FormatUtil.parseFlexible("&8[&6ClasherCity&8] "));
        String mini = PlainTextComponentSerializer.plainText().serialize(FormatUtil.parseFlexible("<gold>ClasherCity</gold>"));
        assertEquals("[ClasherCity] ", legacy);
        assertEquals("ClasherCity", mini);
    }
}
