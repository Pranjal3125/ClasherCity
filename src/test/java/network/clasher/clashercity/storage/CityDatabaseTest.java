package network.clasher.clashercity.storage;

import network.clasher.clashercity.model.Area;
import network.clasher.clashercity.model.Phase;
import network.clasher.clashercity.model.Plot;
import network.clasher.clashercity.model.ResultRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class CityDatabaseTest {

    @Test
    void plotOverlapLookupAndTournamentScoringWorkEndToEnd(@TempDir Path tempDir) throws Exception {
        try (CityDatabase db = new CityDatabase(tempDir.resolve("test-city.db").toFile())) {
            assertEquals(Phase.SETUP, db.phase());
            db.startNewTournament("Spring Cup");
            assertEquals(Phase.REGISTRATION, db.phase());
            assertEquals("Spring Cup", db.tournamentName());

            // Insert two non-overlapping plots and one road area
            db.insertPlot(new Plot("A-01", "clasher_city", 0, 0, 15, 15, 1000.0, null, null));
            db.insertPlot(new Plot("A-02", "clasher_city", 20, 0, 35, 15, 1500.0, null, null));
            db.insertArea(new Area("ROAD-01", "clasher_city", "ROAD", 16, 0, 19, 15));

            assertTrue(db.plotExists("a-01"));
            assertTrue(db.overlapsAny("clasher_city", 10, 10, 22, 12)); // overlaps A-01 + ROAD-01 + A-02
            assertFalse(db.overlapsAny("clasher_city", 50, 50, 65, 65));

            Plot hit = db.plotAt("clasher_city", 5, 5);
            assertNotNull(hit);
            assertEquals("A-01", hit.id());
            assertNull(db.plotAt("clasher_city", 17, 5)); // on road

            // Enter both plots into tournament
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            db.addTournamentEntry("A-01", alice, "Alice");
            db.addTournamentEntry("A-02", bob, "Bob");
            assertTrue(db.isTournamentEntry("A-01"));
            assertEquals(1, db.countTournamentEntries(alice));

            // Public voting: 2 votes for A-01, 1 vote for A-02
            UUID voter1 = UUID.randomUUID();
            UUID voter2 = UUID.randomUUID();
            UUID voter3 = UUID.randomUUID();
            db.recordVote(voter1, "Voter1", "A-01");
            db.recordVote(voter2, "Voter2", "A-01");
            db.recordVote(voter3, "Voter3", "A-02");
            // Duplicate vote by voter1 must fail unique constraint
            assertThrows(SQLException.class, () -> db.recordVote(voter1, "Voter1", "A-02"));

            // Judge scoring: Judge1 scores all 5 categories for A-02 (total 90) and only 2 categories for A-01 (incomplete -> 0)
            UUID judge = UUID.randomUUID();
            db.addJudge(judge, "Judge1");
            assertTrue(db.isJudge(judge));
            db.saveScore(judge, "A-02", "creativity", 22);
            db.saveScore(judge, "A-02", "exterior", 23);
            db.saveScore(judge, "A-02", "interior", 18);
            db.saveScore(judge, "A-02", "detailing", 14);
            db.saveScore(judge, "A-02", "theme", 13);

            db.saveScore(judge, "A-01", "creativity", 25);
            db.saveScore(judge, "A-01", "exterior", 25);

            // 50/50 weights: A-02 = 0.5*(33.33) + 0.5*(90) = 61.67; A-01 = 0.5*(66.67) + 0.5*(0) = 33.33
            List<ResultRow> results = db.calculateResults(5, 50.0, 50.0);
            assertEquals(2, results.size());
            assertEquals("A-02", results.get(0).plotId());
            assertEquals(90.0, results.get(0).judgeScore(), 0.01);
            assertEquals("A-01", results.get(1).plotId());
            assertEquals(0.0, results.get(1).judgeScore(), 0.01);

            // Manual winner override forces A-01 to rank 1
            db.setMeta("winner_1", "A-01");
            List<ResultRow> overridden = db.calculateResults(5, 50.0, 50.0);
            assertEquals("A-01", overridden.get(0).plotId());

            // Reward queue lifecycle
            db.queueReward(alice, "Alice", 1, "A-01");
            assertEquals(1, db.pendingRewards(alice).size());
            db.markRewardStatus(db.tournamentId(), alice, 1, "REVIEW");
            assertEquals(0, db.pendingRewards(alice).size());
            assertEquals(1, db.resetRewardsForRetry(alice));
            assertEquals(1, db.pendingRewards(alice).size());
            db.markRewardStatus(db.tournamentId(), alice, 1, "DELIVERED");
            assertEquals(0, db.pendingRewards(alice).size());
        }
    }
}
