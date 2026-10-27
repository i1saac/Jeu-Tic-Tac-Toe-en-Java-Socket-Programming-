import javax.swing.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordonne un tournoi à élimination directe (4, 8 ou 16 joueurs).
 */
public class TournamentManager {
    private final ServeurXO server;
    private final AtomicInteger tournamentIdSeq = new AtomicInteger(1);
    private volatile int configuredBracketSize = 8;

    private final List<ServeurXO.PlayerHandler> joinQueue = new CopyOnWriteArrayList<>();
    private volatile ActiveTournament current;
    private final List<TournamentHistory> history = new CopyOnWriteArrayList<>();
    private TournamentBracketWindow dashboardRef;

    public TournamentManager(ServeurXO server) {
        this.server = server;
    }

    public void setDashboard(TournamentBracketWindow w) {
        this.dashboardRef = w;
    }

    void refreshDashboard() {
        if (dashboardRef != null) {
            SwingUtilities.invokeLater(dashboardRef::refreshFromServer);
        }
    }

    public int getConfiguredBracketSize() {
        return configuredBracketSize;
    }

    public void setConfiguredBracketSize(int size) {
        if (size != 4 && size != 8 && size != 16) {
            throw new IllegalArgumentException("Taille invalide");
        }
        this.configuredBracketSize = size;
    }

    public List<TournamentHistory> getHistorySnapshot() {
        return List.copyOf(history);
    }

    public ActiveTournament getCurrentForView() {
        return current;
    }

    public BracketMatch findBracketMatch(int tid, int round, int slot) {
        ActiveTournament t = current;
        if (t == null || tid < 0) {
            return null;
        }
        return t.findMatch(tid, round, slot);
    }

    public int getQueueCount() {
        return joinQueue.size();
    }

    public synchronized void joinQueue(ServeurXO.PlayerHandler p) {
        if (current != null && current.containsPlayer(p)) {
            p.send("INFO:Vous êtes déjà dans le tournoi en cours.");
            return;
        }
        if (!p.isConnected() || p.getStatus() != ServeurXO.PlayerStatus.CONNECTED) {
            p.send("INFO:Indisponible pour le tournoi.");
            return;
        }
        if (joinQueue.contains(p)) {
            broadcastQueueProgress();
            return;
        }
        joinQueue.add(p);
        p.tournamentQueued = true;
        server.appendLog("TOURNOI", p.getPlayerName() + " file tournoi (" + joinQueue.size() + "/" + configuredBracketSize + ")");
        broadcastQueueProgress();
        tryStartTournamentLocked();
        refreshDashboard();
    }

    public synchronized void leaveQueue(ServeurXO.PlayerHandler p) {
        joinQueue.remove(p);
        p.tournamentQueued = false;
        broadcastQueueProgress();
        refreshDashboard();
    }

    private void broadcastQueueProgress() {
        String msg = "TOURNAMENT_QUEUE:" + joinQueue.size() + "/" + configuredBracketSize;
        for (ServeurXO.PlayerHandler q : joinQueue) {
            q.send(msg);
        }
    }

    public void broadcastQueueProgressTo(ServeurXO.PlayerHandler p) {
        if (p.tournamentQueued) {
            p.send("TOURNAMENT_QUEUE:" + joinQueue.size() + "/" + configuredBracketSize);
        }
    }

    public void notifyPlayerQueueState(ServeurXO.PlayerHandler p) {
        if (p.tournamentQueued && current == null) {
            broadcastQueueProgressTo(p);
        } else if (current != null && current.containsPlayer(p)) {
            p.send("TOURNAMENT_ACTIVE_INFO:" + current.tournamentId + ":" + current.bracketSize);
        }
    }

    public synchronized void onPlayerDisconnected(ServeurXO.PlayerHandler p) {
        joinQueue.remove(p);
        p.tournamentQueued = false;
        ActiveTournament t = current;
        if (t != null) {
            t.onPlayerDisconnected(p, server, this);
        }
        broadcastQueueProgress();
        refreshDashboard();
    }

    private synchronized void tryStartTournamentLocked() {
        if (current != null || joinQueue.size() < configuredBracketSize || configuredBracketSize < 4) {
            return;
        }

        List<ServeurXO.PlayerHandler> batch = new ArrayList<>();
        for (ServeurXO.PlayerHandler cand : joinQueue) {
            if (batch.size() >= configuredBracketSize) {
                break;
            }
            if (cand.isConnected() && cand.getStatus() == ServeurXO.PlayerStatus.CONNECTED) {
                batch.add(cand);
            }
        }
        if (batch.size() < configuredBracketSize) {
            return;
        }
        joinQueue.removeAll(batch);
        for (ServeurXO.PlayerHandler pl : batch) {
            pl.tournamentQueued = false;
        }

        int tid = tournamentIdSeq.getAndIncrement();
        ActiveTournament tm = new ActiveTournament(tid, configuredBracketSize, batch, Instant.now());
        current = tm;
        server.appendLog("TOURNOI", "Tournoi #" + tid + " (" + configuredBracketSize + " joueurs)");
        tm.beginRoundZero(server, this);
        broadcastQueueProgress();
        refreshDashboard();
    }

    public void onMatchResolved(BracketMatch match, ServeurXO.PlayerHandler winner,
                                ServeurXO.PlayerHandler loser) {
        ActiveTournament t;
        synchronized (this) {
            t = current;
        }
        if (t == null) {
            return;
        }
        synchronized (t.lock) {
            t.onMatchResolved(match, winner, loser, server, this);
        }
        refreshDashboard();
    }

    public synchronized void cancelActiveDueToAdmin() {
        ActiveTournament t = current;
        if (t == null) {
            return;
        }
        synchronized (t.lock) {
            t.cancelDueToAdmin(server, this);
        }
        server.appendLog("TOURNOI", "Tournoi annulé (admin).");
        broadcastQueueProgress();
        refreshDashboard();
    }

    void championFound(ServeurXO.PlayerHandler champ, ActiveTournament t) {
        List<String> names = new ArrayList<>(t.participantNames);
        history.add(new TournamentHistory(
                t.tournamentId,
                Instant.now(),
                names,
                champ.getPlayerName(),
                champ.remoteHost(),
                t.bracketSize));
        champ.send("TOURNAMENT_CHAMP:" + champ.getPlayerName());
        champ.send("RETURN_TO_MENU");
        champ.send("CLASSIFICATION:" + server.buildClassificationPayload());
        server.incrementTournamentsWon(champ.getPlayerName());
        server.refreshClassificationTable();
        server.broadcastClassificationToClients();
        champ.send("PROFILE:" + champ.remoteHost() + "|" + champ.remotePort() + "|connecté");

        synchronized (this) {
            current = null;
        }
        broadcastQueueProgress();
        tryStartTournamentLocked();
        refreshDashboard();
    }

    void finishTournamentCanceled() {
        synchronized (this) {
            current = null;
        }
        broadcastQueueProgress();
        refreshDashboard();
    }

    // ---------------- Bracket model ----------------

    public static final class BracketMatch {
        final int tournamentId;
        final int round;
        final int index;
        volatile ServeurXO.PlayerHandler leftPlayer;
        volatile ServeurXO.PlayerHandler rightPlayer;
        volatile ServeurXO.PlayerHandler winner;
        volatile int activeGameId = -1;

        BracketMatch(int tournamentId, int round, int index) {
            this.tournamentId = tournamentId;
            this.round = round;
            this.index = index;
        }

        boolean involves(ServeurXO.PlayerHandler p) {
            return p == leftPlayer || p == rightPlayer;
        }

        Map<String, String> toViewRow() {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("round", Integer.toString(round + 1));
            row.put("index", Integer.toString(index + 1));
            row.put("A", leftPlayer != null ? leftPlayer.getPlayerName() : "—");
            row.put("B", rightPlayer != null ? rightPlayer.getPlayerName() : "—");
            row.put("stat", winner != null ? "★ " + winner.getPlayerName() : (activeGameId > 0 ? "en cours" : "attente"));
            return row;
        }
    }

    public static final class ActiveTournament {
        final Object lock = new Object();
        final int tournamentId;
        final int bracketSize;
        final Instant startedAt;
        final List<String> participantNames;
        final List<ServeurXO.PlayerHandler> entrants;
        final BracketMatch[][] rounds;
        final int numRounds;
        volatile boolean cancelled;

        ActiveTournament(int tournamentId, int bracketSize,
                         List<ServeurXO.PlayerHandler> entrants, Instant startedAt) {
            this.tournamentId = tournamentId;
            this.bracketSize = bracketSize;
            this.startedAt = startedAt;
            this.entrants = List.copyOf(entrants);
            List<String> pn = new ArrayList<>();
            for (ServeurXO.PlayerHandler q : entrants) {
                pn.add(q.getPlayerName());
            }
            this.participantNames = Collections.unmodifiableList(pn);

            int nr = 0;
            for (int x = bracketSize; x > 1; x /= 2) {
                nr++;
            }
            this.numRounds = nr;
            this.rounds = new BracketMatch[nr][];
            for (int r = 0; r < nr; r++) {
                int count = bracketSize >> (r + 1);
                rounds[r] = new BracketMatch[count];
                for (int i = 0; i < count; i++) {
                    rounds[r][i] = new BracketMatch(tournamentId, r, i);
                }
            }
        }

        boolean containsPlayer(ServeurXO.PlayerHandler p) {
            synchronized (lock) {
                for (BracketMatch[] rr : rounds) {
                    for (BracketMatch mm : rr) {
                        if (mm.involves(p)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        BracketMatch findMatch(int tid, int round, int slot) {
            if (tid != this.tournamentId || round < 0 || round >= numRounds) {
                return null;
            }
            int len = rounds[round].length;
            if (slot < 0 || slot >= len) {
                return null;
            }
            return rounds[round][slot];
        }

        void beginRoundZero(ServeurXO srv, TournamentManager mgr) {
            synchronized (lock) {
                cancelled = false;
                int pairs = bracketSize >> 1;
                for (int i = 0; i < pairs; i++) {
                    BracketMatch m = rounds[0][i];
                    ServeurXO.PlayerHandler a = entrants.get(2 * i);
                    ServeurXO.PlayerHandler b = entrants.get(2 * i + 1);
                    m.leftPlayer = a;
                    m.rightPlayer = b;
                    m.activeGameId = srv.startBracketGame(a, b, m);
                }
                for (ServeurXO.PlayerHandler p : entrants) {
                    p.send("TOURNAMENT_STARTED:" + tournamentId + ":" + bracketSize);
                }
            }
        }

        void onMatchResolved(BracketMatch match, ServeurXO.PlayerHandler winner,
                             ServeurXO.PlayerHandler loser, ServeurXO srv, TournamentManager mgr) {
            synchronized (lock) {
                if (cancelled) {
                    return;
                }
                if (match.winner != null) {
                    return;
                }
                match.winner = winner;

                loser.send("TOURNAMENT_ELIMINATED");
                loser.send("RETURN_TO_MENU");
                loser.send("CLASSIFICATION:" + srv.buildClassificationPayload());
                loser.send("PROFILE:" + loser.remoteHost() + "|" + loser.remotePort() + "|connecté");

                srv.appendLog("TOURNOI", "Éliminé : " + loser.getPlayerName() + " — " + winner.getPlayerName() + " avance");

                if (match.round >= numRounds - 1) {
                    mgr.championFound(winner, this);
                    return;
                }

                BracketMatch parent = rounds[match.round + 1][match.index / 2];
                if (match.index % 2 == 0) {
                    parent.leftPlayer = winner;
                } else {
                    parent.rightPlayer = winner;
                }

                winner.send("TOURNAMENT_ROUND_ADVANCED:" + (match.round + 2));

                ServeurXO.PlayerHandler wA = parent.leftPlayer;
                ServeurXO.PlayerHandler wB = parent.rightPlayer;

                if (wA != null && wB != null) {
                    parent.activeGameId = srv.startBracketGame(wA, wB, parent);
                    wA.send("TOURNAMENT_MATCH:" + tournamentId + ":R" + (parent.round + 1));
                    wB.send("TOURNAMENT_MATCH:" + tournamentId + ":R" + (parent.round + 1));
                } else {
                    winner.send("TOURNAMENT_WAIT_NEXT:En attente du prochain tour...");
                }
            }
        }

        void onPlayerDisconnected(ServeurXO.PlayerHandler disconnected, ServeurXO srv, TournamentManager mgr) {
            synchronized (lock) {
                if (cancelled) {
                    return;
                }
                if (entrants.contains(disconnected)) {
                    cancelWhole(srv, mgr);
                }
            }
        }

        void cancelDueToAdmin(ServeurXO srv, TournamentManager mgr) {
            cancelled = true;
            for (ServeurXO.PlayerHandler p : entrants) {
                if (p != null && p.isConnected()) {
                    p.send("TOURNAMENT_CANCELLED:Administrateur.");
                    p.send("RETURN_TO_MENU");
                    p.send("CLASSIFICATION:" + srv.buildClassificationPayload());
                    p.send("PROFILE:" + p.remoteHost() + "|" + p.remotePort() + "|connecté");
                }
            }
            mgr.finishTournamentCanceled();
        }

        private void cancelWhole(ServeurXO srv, TournamentManager mgr) {
            cancelled = true;
            srv.appendLog("ERROR", "Tournoi #" + tournamentId + " annulé (déconnexion).");
            for (ServeurXO.PlayerHandler p : entrants) {
                if (p != null && p.isConnected()) {
                    p.send("TOURNAMENT_CANCELLED:Déconnexion.");
                    p.send("RETURN_TO_MENU");
                    p.send("CLASSIFICATION:" + srv.buildClassificationPayload());
                    p.send("PROFILE:" + p.remoteHost() + "|" + p.remotePort() + "|connecté");
                }
            }
            mgr.finishTournamentCanceled();
        }

        public List<List<Map<String, String>>> viewModel() {
            List<List<Map<String, String>>> col = new ArrayList<>();
            synchronized (lock) {
                for (int r = 0; r < numRounds; r++) {
                    List<Map<String, String>> lst = new ArrayList<>();
                    for (BracketMatch m : rounds[r]) {
                        lst.add(m.toViewRow());
                    }
                    col.add(lst);
                }
            }
            return col;
        }
    }
}
