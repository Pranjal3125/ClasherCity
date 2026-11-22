package network.clasher.clashercity.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Adventure MiniMessage + legacy '&' color formatting and identifier validation helpers.
 */
public final class FormatUtil {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_AMP = LegacyComponentSerializer.legacyAmpersand();

    private FormatUtil() {}

    public static Component mm(String input) {
        return MM.deserialize(input == null ? "" : input);
    }

    public static Component parseFlexible(String input) {
        if (input == null || input.isEmpty()) return Component.empty();
        if (input.contains("<") && input.contains(">")) return MM.deserialize(input);
        return LEGACY_AMP.deserialize(input);
    }

    public static Component prefixed(String prefixConfig, String body) {
        return parseFlexible(prefixConfig).append(parseFlexible(body));
    }

    public static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static String escape(String raw) {
        return raw == null ? "" : raw.replace("<", "\\<");
    }

    public static void validateId(String id) {
        if (id == null || id.length() > 32 || !id.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("ID must be 1-32 characters using letters, numbers, _ or -.");
        }
    }
}
