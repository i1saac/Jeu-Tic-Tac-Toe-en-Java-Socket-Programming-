import java.time.Instant;
import java.util.Collections;
import java.util.List;

/** Enregistrement immuable d'un tournoi terminé pour l'historique admin. */
public final class TournamentHistory {
    private final int tournamentId;
    private final Instant finishedAt;
    private final List<String> participants;
    private final String championName;
    private final String championIp;
    private final int bracketSize;

    public TournamentHistory(int tournamentId, Instant finishedAt, List<String> participants,
                           String championName, String championIp, int bracketSize) {
        this.tournamentId = tournamentId;
        this.finishedAt = finishedAt;
        this.participants = Collections.unmodifiableList(participants);
        this.championName = championName;
        this.championIp = championIp;
        this.bracketSize = bracketSize;
    }

    public int getTournamentId() {
        return tournamentId;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public List<String> getParticipants() {
        return participants;
    }

    public String getChampionName() {
        return championName;
    }

    public String getChampionIp() {
        return championIp;
    }

    public int getBracketSize() {
        return bracketSize;
    }
}
