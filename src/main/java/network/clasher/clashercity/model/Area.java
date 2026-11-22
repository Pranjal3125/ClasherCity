package network.clasher.clashercity.model;

/**
 * Represents a protected non-buildable city road or public area.
 */
public record Area(
        String id,
        String world,
        String type,
        int minX,
        int minZ,
        int maxX,
        int maxZ
) {}
