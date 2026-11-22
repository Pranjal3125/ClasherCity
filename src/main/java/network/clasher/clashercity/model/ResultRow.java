package network.clasher.clashercity.model;

/**
 * Computed tournament leaderboard row combining player vote share and judge rubric averages.
 */
public record ResultRow(
        String plotId,
        String ownerUuid,
        String ownerName,
        int votes,
        double playerScore,
        double judgeScore,
        double finalScore
) {}
