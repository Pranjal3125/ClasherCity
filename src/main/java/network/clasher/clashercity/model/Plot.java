package network.clasher.clashercity.model;

/**
 * Represents a rectangular city building plot stored in SQLite.
 */
public record Plot(
        String id,
        String world,
        int minX,
        int minZ,
        int maxX,
        int maxZ,
        double price,
        String ownerUuid,
        String ownerName
) {
    public int width() { return maxX - minX + 1; }
    public int depth() { return maxZ - minZ + 1; }
    public boolean isOwned() { return ownerUuid != null && !ownerUuid.isBlank(); }
}
