import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class ServeurXO extends JFrame {
    private static final DateTimeFormatter LOG_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final JTextField portField = new JTextField("1234", 8);
    private final JButton startButton = new JButton("Démarrer");
    private final JButton stopButton = new JButton("Arrêter");
    private final JLabel bindInfoLabel = new JLabel("Serveur à l’arrêt — IP / port affichés après démarrage.", SwingConstants.LEFT);
    private final JButton gamesButton = new JButton("Parties");
    private final JButton classificationButton = new JButton("Classification");
    private final JButton historyButton = new JButton("Historique terminées");
    private final JButton exportLogButton = new JButton("Exporter le journal...");
    private final JButton refreshPlayersButton = new JButton("Rafraîchir joueurs");
    private final JButton disconnectPlayerButton = new JButton("Déconnecter joueur");
    private final JButton forceCloseGameButton = new JButton("Clore partie (ID)");
    private final JButton tournamentDashboardButton = new JButton("Tableau tournoi");
    private final JComboBox<Integer> tournamentSizeCombo = new JComboBox<>(new Integer[]{4, 8, 16});

    private final DefaultListModel<String> playersListModel = new DefaultListModel<>();
    private final JList<String> playersList = new JList<>(playersListModel);

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel centerPanel = new JPanel(cardLayout);
    private final DefaultTableModel gamesModel;
    private final JTable gamesTable;
    private final DefaultTableModel classModel;
    private final JTextArea logArea = new JTextArea(8, 40);

    private final ArrayDeque<PlayerHandler> waitingQueue = new ArrayDeque<>();
    private final Map<Integer, GameRecord> gameRecords = new ConcurrentHashMap<>();
    private final Map<Integer, GameSession> activeGames = new ConcurrentHashMap<>();
    private final Map<String, PlayerHandler> playersByName = new ConcurrentHashMap<>();
    private final Map<String, PlayerStats> statsByName = new ConcurrentHashMap<>();
    private final Map<String, String> pendingChallengeByTarget = new ConcurrentHashMap<>();
    private final AtomicInteger gameIdCounter = new AtomicInteger(1);
    private final TournamentManager tournamentManager = new TournamentManager(this);

    private final ConcurrentLinkedDeque<FinishedGameSnapshot> finishedGames = new ConcurrentLinkedDeque<>();
    private static final int MAX_FINISHED_HISTORY = 500;

    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public ServeurXO() {
        setTitle("Serveur Morpion — administration");
        setSize(1200, 700);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        JPanel top = new JPanel(new BorderLayout(8, 4));
        JPanel topRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        topRow.add(new JLabel("Port :"));
        topRow.add(portField);
        topRow.add(startButton);
        topRow.add(stopButton);
        topRow.add(gamesButton);
        topRow.add(classificationButton);
        topRow.add(historyButton);
        topRow.add(exportLogButton);
        topRow.add(new JLabel("Taille tournoi :"));
        tournamentSizeCombo.setSelectedItem(8);
        topRow.add(tournamentSizeCombo);
        topRow.add(tournamentDashboardButton);

        JPanel adminRow = new JPanel(new BorderLayout(8, 0));
        adminRow.add(new JLabel("Joueurs connectés :"), BorderLayout.WEST);
        JPanel listWrap = new JPanel(new BorderLayout());
        listWrap.setPreferredSize(new Dimension(320, 90));
        listWrap.add(new JScrollPane(playersList), BorderLayout.CENTER);
        JPanel adminBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        adminBtns.add(refreshPlayersButton);
        adminBtns.add(disconnectPlayerButton);
        adminBtns.add(forceCloseGameButton);
        adminRow.add(listWrap, BorderLayout.CENTER);
        adminRow.add(adminBtns, BorderLayout.EAST);

        JPanel topCombined = new JPanel(new BorderLayout());
        topCombined.add(topRow, BorderLayout.NORTH);
        JPanel bindRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bindRow.add(bindInfoLabel);
        topCombined.add(bindRow, BorderLayout.CENTER);

        top.add(topCombined, BorderLayout.NORTH);
        top.add(adminRow, BorderLayout.SOUTH);

        String[] gameColumns = {"Jeu", "Type", "État", "Joueur X", "Joueur O", "IPs joueurs", "Coups", "Résultat", "Voir"};
        gamesModel = new DefaultTableModel(gameColumns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 8;
            }
        };
        gamesTable = new JTable(gamesModel);
        gamesTable.getColumn("Voir").setCellRenderer(new DetailButtonRenderer());
        gamesTable.getColumn("Voir").setCellEditor(new DetailButtonEditor(new JCheckBox(), this, gamesTable));

        String[] classColumns = {"#", "Joueur", "Parties", "Gagnées", "Perdues", "Nulles", "Tournois", "Diff"};
        classModel = new DefaultTableModel(classColumns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable classTable = new JTable(classModel);

        JScrollPane gamesScroll = new JScrollPane(gamesTable);
        JScrollPane classScroll = new JScrollPane(classTable);
        centerPanel.add(gamesScroll, "games");
        centerPanel.add(classScroll, "classification");

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.add(new JLabel("Journal horodaté", SwingConstants.LEFT), BorderLayout.NORTH);
        logPanel.add(logScroll, BorderLayout.CENTER);

        JPanel upperCard = new JPanel(new BorderLayout());
        upperCard.add(centerPanel, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, upperCard, logPanel);
        split.setResizeWeight(0.62);
        split.setDividerLocation(380);

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        startButton.addActionListener(e -> startServer());
        stopButton.addActionListener(e -> stopServer());
        stopButton.setEnabled(false);
        gamesButton.addActionListener(e -> cardLayout.show(centerPanel, "games"));
        classificationButton.addActionListener(e -> cardLayout.show(centerPanel, "classification"));
        historyButton.addActionListener(e -> showHistoryDialog());
        exportLogButton.addActionListener(e -> exportLog());
        refreshPlayersButton.addActionListener(e -> refreshPlayersListUi());
        disconnectPlayerButton.addActionListener(e -> adminDisconnectSelectedPlayer());
        forceCloseGameButton.addActionListener(e -> adminForceCloseGame());
        tournamentDashboardButton.addActionListener(e ->
                SwingUtilities.invokeLater(() -> new TournamentBracketWindow(this, tournamentManager).setVisible(true)));
        tournamentSizeCombo.addActionListener(e -> {
            Integer z = (Integer) tournamentSizeCombo.getSelectedItem();
            if (z != null) {
                tournamentManager.setConfiguredBracketSize(z);
                appendLog("TOURNOI", "Taille de bracket réglée sur " + z);
            }
        });

        gamesButton.setEnabled(false);
        classificationButton.setEnabled(false);

        appendLog("INFO", "Interface prête. Démarrez le serveur pour accepter des connexions.");

        applyServerLooks(top, topRow, bindRow, adminRow, listWrap,
                upperCard, centerPanel, split, gamesScroll, classScroll,
                gamesTable, classTable, logScroll, logPanel);

        XOTheme.stylePrimaryButton(startButton);
        XOTheme.styleSecondaryButton(stopButton);
        XOTheme.stylePrimaryButton(gamesButton);
        XOTheme.stylePrimaryButton(classificationButton);
        XOTheme.styleSecondaryButton(historyButton);
        XOTheme.styleSecondaryButton(exportLogButton);
        XOTheme.styleTextField(portField);
        XOTheme.styleSecondaryButton(refreshPlayersButton);
        XOTheme.styleSecondaryButton(disconnectPlayerButton);
        XOTheme.styleSecondaryButton(forceCloseGameButton);
        tintLabelsRecursive(topRow, XOTheme.TEXT_DIM);
        tintLabelsRecursive(bindRow, XOTheme.TEXT_DIM);
        tintLabelsRecursive(adminRow, XOTheme.TEXT_DIM);
        XOTheme.styleAccentTitle(bindInfoLabel);

        XOTheme.styleSecondaryButton(tournamentDashboardButton);
        tournamentSizeCombo.setBackground(XOTheme.NAVY_PANEL);
        tournamentSizeCombo.setForeground(XOTheme.TEXT_BRIGHT);

        XOTheme.styleList(playersList);
    }

    private static void tintLabelsRecursive(Container root, Color fg) {
        for (Component ch : root.getComponents()) {
            if (ch instanceof JLabel jl) {
                jl.setForeground(fg);
            }
            if (ch instanceof Container sub) {
                tintLabelsRecursive(sub, fg);
            }
        }
    }

    /** Appliqué après la construction des références locales dans le constructeur. */
    private void applyServerLooks(JPanel topBar, JPanel topRow, JPanel bindRow, JPanel adminRow,
                                  JPanel playersListOuter, JPanel tablesShell, JPanel tablesCardPanel,
                                  JSplitPane splitter, JScrollPane gamesScrollWrap, JScrollPane classScrollWrap,
                                  JTable gamesTbl, JTable classTbl, JScrollPane logScrollOuter, JPanel logPanelWrap) {

        JPanel root = (JPanel) getContentPane();
        root.setOpaque(true);
        root.setBackground(XOTheme.BLACK_BLUE);

        topBar.setOpaque(true);
        topBar.setBackground(XOTheme.NAVY_PANEL);
        topBar.setBorder(BorderFactory.createMatteBorder(0, 0, 2, 0, XOTheme.BORDER_BLUE));

        topRow.setOpaque(true);
        topRow.setBackground(XOTheme.NAVY_PANEL);
        bindRow.setOpaque(true);
        bindRow.setBackground(XOTheme.NAVY_PANEL);
        adminRow.setOpaque(true);
        adminRow.setBackground(XOTheme.NAVY_PANEL);
        playersListOuter.setOpaque(true);
        playersListOuter.setBackground(XOTheme.BLACK_BLUE);

        tablesShell.setOpaque(true);
        tablesShell.setBackground(XOTheme.BLACK_BLUE);
        tablesCardPanel.setOpaque(true);
        tablesCardPanel.setBackground(XOTheme.BLACK_BLUE);
        splitter.setBorder(BorderFactory.createLineBorder(XOTheme.BORDER_BLUE));
        splitter.setBackground(XOTheme.NAVY_PANEL);

        XOTheme.styleTable(gamesTbl);
        XOTheme.styleScrollPane(gamesScrollWrap, XOTheme.CELL_FACE);
        XOTheme.styleTable(classTbl);
        XOTheme.styleScrollPane(classScrollWrap, XOTheme.CELL_FACE);

        logPanelWrap.setOpaque(true);
        logPanelWrap.setBackground(XOTheme.BLACK_BLUE);
        tintLabelsRecursive(logPanelWrap, XOTheme.TEXT_DIM);
        logArea.setOpaque(true);
        logArea.setBackground(XOTheme.LOG_BG);
        logArea.setForeground(XOTheme.ACCENT_BRIGHT);
        logArea.setCaretColor(XOTheme.ACCENT_BRIGHT);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        XOTheme.styleScrollPane(logScrollOuter, XOTheme.LOG_BG);
    }

    void appendLog(String level, String message) {
        String line = "[" + LOG_FMT.format(Instant.now()) + "] [" + level + "] " + message;
        SwingUtilities.invokeLater(() -> {
            logArea.append(line + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void exportLog() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Exporter le journal");
        int r = chooser.showSaveDialog(this);
        if (r != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path path = chooser.getSelectedFile().toPath();
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".json")) {
                String escaped = escapeJson(logArea.getText());
                Files.writeString(path,
                        "{\"export\":\"journal_serveur_morpion\",\"generated\":\"" +
                                Instant.now().toString() + "\",\"lines\":" + escaped + "}\n",
                        StandardCharsets.UTF_8);
            } else {
                Files.writeString(path, logArea.getText(), StandardCharsets.UTF_8);
            }
            appendLog("EXPORT", "Journal écrit dans " + path);
        } catch (IOException ex) {
            appendLog("ERROR", "Export impossible : " + ex.getMessage());
            JOptionPane.showMessageDialog(this, "Export impossible : " + ex.getMessage());
        }
    }

    private static String escapeJson(String raw) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private void showHistoryDialog() {
        List<FinishedGameSnapshot> snapshot = finishedGames.stream().collect(Collectors.toList());
        String[] cols = {"Date", "ID", "État", "X", "O", "IPs", "Coups", "Résultat"};
        DefaultTableModel m = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        for (FinishedGameSnapshot g : snapshot) {
            m.addRow(new Object[]{
                    LOG_FMT.format(Instant.ofEpochMilli(g.whenMs)),
                    g.gameId,
                    g.state,
                    g.playerX,
                    g.playerO,
                    g.ipsSummary,
                    g.moveCount,
                    g.resultSummary
            });
        }
        JTable t = new JTable(m);
        XOTheme.styleTable(t);
        JScrollPane historyScroll = new JScrollPane(t);
        XOTheme.styleScrollPane(historyScroll, XOTheme.CELL_FACE);
        JDialog dlg = new JDialog(this, "Parties terminées (plus récentes en haut)", true);
        JPanel dlgRoot = new JPanel(new BorderLayout());
        dlgRoot.setOpaque(true);
        dlgRoot.setBackground(XOTheme.NAVY_PANEL);
        dlgRoot.add(historyScroll, BorderLayout.CENTER);
        dlg.setContentPane(dlgRoot);
        dlg.setSize(900, 400);
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    private void refreshPlayersListUi() {
        SwingUtilities.invokeLater(() -> {
            playersListModel.clear();
            for (PlayerHandler p : playersByName.values()) {
                if (p.name != null) {
                    playersListModel.addElement(p.name + " — " + p.remoteHost() + ":" + p.remotePort()
                            + " [" + p.status + "]");
                }
            }
        });
    }

    private void adminDisconnectSelectedPlayer() {
        int idx = playersList.getSelectedIndex();
        if (idx < 0) {
            JOptionPane.showMessageDialog(this, "Sélectionnez un joueur dans la liste.");
            return;
        }
        String line = playersListModel.getElementAt(idx);
        String playerName = line.split(" — ")[0].trim();
        PlayerHandler ph = playersByName.get(playerName);
        if (ph != null) {
            appendLog("ADMIN", "Déconnexion forcée du joueur " + playerName);
            ph.send("BYE");
            ph.close();
            refreshPlayersListUi();
        }
    }

    private void adminForceCloseGame() {
        String idTxt = JOptionPane.showInputDialog(this, "Identifiant de la partie à clore :");
        if (idTxt == null) {
            return;
        }
        try {
            int gid = Integer.parseInt(idTxt.trim());
            GameSession session = activeGames.get(gid);
            if (session == null) {
                JOptionPane.showMessageDialog(this, "Aucune partie active avec cet ID.");
                return;
            }
            appendLog("ADMIN", "Fermeture forcée partie " + gid);
            session.forceStopByAdmin();
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "ID invalide.");
        }
    }

    void registerActiveGame(int gameId, GameSession session) {
        activeGames.put(gameId, session);
    }

    void unregisterActiveGame(int gameId) {
        activeGames.remove(gameId);
    }

    private String localAddressesSummary() {
        Set<String> ips = new LinkedHashSet<>();
        try {
            ips.add(InetAddress.getLocalHost().getHostAddress());
        } catch (IOException ignored) {
        }
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a.getHostAddress().indexOf(':') < 0) {
                        ips.add(a.getHostAddress());
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return String.join(", ", ips);
    }

    private void startServer() {
        if (running) {
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Numéro de port invalide.");
            return;
        }

        running = true;
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        portField.setEnabled(false);
        gamesButton.setEnabled(true);
        classificationButton.setEnabled(true);

        acceptThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                String ips = localAddressesSummary();
                SwingUtilities.invokeLater(() ->
                        bindInfoLabel.setText("Écoute sur le port " + port + " — adresses locales : " + ips));
                appendLog("SERVER", "Serveur démarré sur le port " + port + " (IP : " + ips + ")");
                while (running) {
                    Socket socket = serverSocket.accept();
                    appendLog("CONNECT", "Connexion entrante " + socket.getInetAddress().getHostAddress()
                            + ":" + socket.getPort());
                    PlayerHandler player = new PlayerHandler(socket, this);
                    new Thread(player, "player-" + socket.getPort()).start();
                }
            } catch (IOException e) {
                if (running) {
                    appendLog("ERROR", "Erreur serveur : " + e.getMessage());
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(this, "Erreur serveur : " + e.getMessage()));
                }
            }
        }, "accept-thread");
        acceptThread.start();
    }

    private synchronized void stopServer() {
        if (!running) {
            return;
        }
        running = false;
        appendLog("SERVER", "Arrêt du serveur demandé.");
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        tournamentManager.cancelActiveDueToAdmin();
        for (PlayerHandler p : new ArrayList<>(playersByName.values())) {
            p.send("BYE");
            p.close();
        }
        gameRecords.clear();
        SwingUtilities.invokeLater(() -> gamesModel.setRowCount(0));
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        portField.setEnabled(true);
        gamesButton.setEnabled(false);
        classificationButton.setEnabled(false);
        bindInfoLabel.setText("Serveur à l’arrêt — IP / port affichés après démarrage.");
        refreshPlayersListUi();
    }

    public synchronized String registerPlayer(PlayerHandler player, String requestedName) {
        String base = requestedName == null || requestedName.trim().isEmpty() ? "Player" : requestedName.trim();
        String candidate = base;
        int n = 1;
        while (playersByName.containsKey(candidate)) {
            candidate = base + "_" + n++;
        }
        player.name = candidate;
        playersByName.put(candidate, player);
        statsByName.putIfAbsent(candidate, new PlayerStats(candidate));
        refreshClassificationTable();
        broadcastClassificationToClients();
        refreshPlayersListUi();
        appendLog("CONNECT", "Joueur enregistré : " + candidate + " depuis " + player.remoteHost() + ":" + player.remotePort());
        return candidate;
    }

    public void disconnectPlayer(PlayerHandler player) {
        if (player == null || player.name == null) {
            return;
        }
        tournamentManager.onPlayerDisconnected(player);
        playersByName.remove(player.name);
        synchronized (waitingQueue) {
            waitingQueue.remove(player);
        }
        pendingChallengeByTarget.values().removeIf(src -> src.equals(player.name));
        pendingChallengeByTarget.remove(player.name);
        refreshClassificationTable();
        broadcastClassificationToClients();
        refreshPlayersListUi();
        appendLog("CONNECT", "Joueur déconnecté : " + player.name);
    }

    public void queueForRandomGame(PlayerHandler player) {
        if (!player.isConnected() || !player.isAvailable()) {
            return;
        }
        synchronized (waitingQueue) {
            if (waitingQueue.contains(player)) {
                return;
            }
            waitingQueue.addLast(player);
            player.status = PlayerStatus.WAITING;

            int waitingGameId = gameIdCounter.getAndIncrement();
            GameRecord waiting = new GameRecord(waitingGameId, "en attente", player.name, "", "", new Plateau());
            waiting.gameType = "Normal";
            waiting.ipX = player.remoteHost();
            waiting.portX = player.remotePort();
            waiting.ipO = "—";
            waiting.portO = 0;
            gameRecords.put(waitingGameId, waiting);
            player.waitingRecordId = waitingGameId;
            updateOrInsertGameRow(waiting);
            player.send("WAITING");
            appendLog("GAME", "File d’attente : " + player.name + " (partie " + waitingGameId + ")");
            matchRandomPlayers();
        }
    }

    private void matchRandomPlayers() {
        while (waitingQueue.size() >= 2) {
            PlayerHandler p1 = waitingQueue.pollFirst();
            PlayerHandler p2 = waitingQueue.pollFirst();
            if (p1 == null || p2 == null) {
                return;
            }
            if (!p1.isAvailable() || !p2.isAvailable()) {
                if (p1.isAvailable()) {
                    waitingQueue.addLast(p1);
                }
                if (p2.isAvailable()) {
                    waitingQueue.addLast(p2);
                }
                return;
            }

            removeGameRecord(p1.waitingRecordId);
            removeGameRecord(p2.waitingRecordId);
            p1.waitingRecordId = -1;
            p2.waitingRecordId = -1;
            startGameBetween(p1, p2);
        }
    }

    public synchronized void requestChallenge(PlayerHandler from, String targetName) {
        PlayerHandler target = playersByName.get(targetName);
        if (target == null || target == from) {
            from.send("INFO:Joueur indisponible.");
            return;
        }
        if (!from.isAvailable() || !target.isAvailable()) {
            from.send("INFO:Un joueur est occupé.");
            return;
        }
        if (pendingChallengeByTarget.containsKey(target.name)) {
            from.send("INFO:Ce joueur a déjà une demande en cours.");
            return;
        }

        pendingChallengeByTarget.put(target.name, from.name);
        target.send("CHALLENGE_FROM:" + from.name);
        from.send("INFO:Invitation envoyée à " + target.name);
        appendLog("GAME", "Défi " + from.name + " → " + target.name);
    }

    public synchronized void answerChallenge(PlayerHandler target, String fromName, boolean accepted) {
        String pendingFrom = pendingChallengeByTarget.get(target.name);
        if (pendingFrom == null || !pendingFrom.equals(fromName)) {
            target.send("INFO:Aucune invitation correspondante.");
            return;
        }
        pendingChallengeByTarget.remove(target.name);

        PlayerHandler from = playersByName.get(fromName);
        if (from == null || !from.isAvailable() || !target.isAvailable()) {
            target.send("INFO:Le joueur n’est plus disponible.");
            if (from != null) {
                from.send("INFO:Défi annulé.");
            }
            appendLog("GAME", "Défi annulé (indisponible) : " + fromName + " / " + target.name);
            return;
        }

        if (accepted) {
            from.send("INFO:" + target.name + " a accepté.");
            target.send("INFO:Vous avez accepté la partie avec " + from.name + ".");
            removeIfWaiting(from);
            removeIfWaiting(target);
            appendLog("GAME", "Défi accepté : " + from.name + " vs " + target.name);
            startGameBetween(from, target);
        } else {
            from.send("INFO:" + target.name + " a refusé votre invitation.");
            target.send("INFO:Vous avez refusé l’invitation.");
            appendLog("GAME", "Défi refusé par " + target.name);
        }
    }

    private void removeIfWaiting(PlayerHandler player) {
        synchronized (waitingQueue) {
            waitingQueue.remove(player);
            removeGameRecord(player.waitingRecordId);
            player.waitingRecordId = -1;
            if (player.status == PlayerStatus.WAITING) {
                player.status = PlayerStatus.CONNECTED;
            }
        }
    }

    public void startGameBetween(PlayerHandler x, PlayerHandler o) {
        if (!x.isConnected() || !o.isConnected()) {
            return;
        }
        int gameId = gameIdCounter.getAndIncrement();
        GameRecord gameRecord = new GameRecord(gameId, "en cours", x.name, o.name, "", new Plateau());
        gameRecord.gameType = "Normal";
        gameRecord.ipX = x.remoteHost();
        gameRecord.portX = x.remotePort();
        gameRecord.ipO = o.remoteHost();
        gameRecord.portO = o.remotePort();
        gameRecords.put(gameId, gameRecord);
        updateOrInsertGameRow(gameRecord);

        x.status = PlayerStatus.IN_GAME;
        o.status = PlayerStatus.IN_GAME;
        new Thread(new GameSession(gameRecord, x, o, this, false), "game-" + gameId).start();
    }

    /**
     * Partie bracket tournoi : enchaîne automatiquement le vainqueur via {@link TournamentManager}.
     */
    public int startBracketGame(PlayerHandler x, PlayerHandler o, TournamentManager.BracketMatch m) {
        if (!x.isConnected() || !o.isConnected()) {
            return -1;
        }
        int gameId = gameIdCounter.getAndIncrement();
        GameRecord gameRecord = new GameRecord(gameId, "en cours", x.name, o.name, "", new Plateau());
        gameRecord.gameType = "Tournoi";
        gameRecord.linkedTournamentId = m.tournamentId;
        gameRecord.linkedRound = m.round;
        gameRecord.linkedSlot = m.index;
        gameRecord.ipX = x.remoteHost();
        gameRecord.portX = x.remotePort();
        gameRecord.ipO = o.remoteHost();
        gameRecord.portO = o.remotePort();
        gameRecords.put(gameId, gameRecord);
        updateOrInsertGameRow(gameRecord);

        x.status = PlayerStatus.IN_GAME;
        o.status = PlayerStatus.IN_GAME;
        x.send("GAME_MODE:TOURNAMENT");
        o.send("GAME_MODE:TOURNAMENT");
        new Thread(new GameSession(gameRecord, x, o, this, true), "tournament-" + gameId).start();
        return gameId;
    }

    public TournamentManager getTournamentManager() {
        return tournamentManager;
    }

    public PlayerHandler findPlayer(String nm) {
        return playersByName.get(nm);
    }

    public void incrementTournamentsWon(String playerName) {
        PlayerStats ps = statsByName.computeIfAbsent(playerName, PlayerStats::new);
        ps.tournamentsWon++;
    }

    public void finishGameForPlayers(PlayerHandler p1, PlayerHandler p2) {
        p1.clearGameQueue();
        p2.clearGameQueue();
        if (p1.isConnected() && p1.status == PlayerStatus.IN_GAME) {
            p1.status = PlayerStatus.CONNECTED;
        }
        if (p2.isConnected() && p2.status == PlayerStatus.IN_GAME) {
            p2.status = PlayerStatus.CONNECTED;
        }
        refreshClassificationTable();
        broadcastClassificationToClients();
    }

    public void updateGameRecord(GameRecord record) {
        gameRecords.put(record.id, record);
        updateOrInsertGameRow(record);
    }

    void recordFinishedGameSnapshot(GameRecord record) {
        String ips = GameRecord.ipSummary(record);
        int coups = record.plateau.nombreCoups();
        FinishedGameSnapshot snap = new FinishedGameSnapshot(
                System.currentTimeMillis(), record.id, record.state,
                record.playerX, record.playerO, ips, coups,
                record.result == null ? "" : record.result);
        finishedGames.addFirst(snap);
        while (finishedGames.size() > MAX_FINISHED_HISTORY) {
            finishedGames.removeLast();
        }
    }

    private void removeGameRecord(int gameId) {
        if (gameId <= 0) {
            return;
        }
        gameRecords.remove(gameId);
        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < gamesModel.getRowCount(); i++) {
                Object idCell = gamesModel.getValueAt(i, 0);
                if (idCell instanceof Integer && ((Integer) idCell) == gameId) {
                    gamesModel.removeRow(i);
                    break;
                }
            }
        });
    }

    private void updateOrInsertGameRow(GameRecord record) {
        SwingUtilities.invokeLater(() -> {
            int row = -1;
            for (int i = 0; i < gamesModel.getRowCount(); i++) {
                Object idCell = gamesModel.getValueAt(i, 0);
                if (idCell instanceof Integer && ((Integer) idCell) == record.id) {
                    row = i;
                    break;
                }
            }
            Object[] data = rowData(record);
            if (row >= 0) {
                for (int c = 0; c < data.length; c++) {
                    gamesModel.setValueAt(data[c], row, c);
                }
            } else {
                gamesModel.addRow(data);
            }
        });
    }

    private static Object[] rowData(GameRecord record) {
        return new Object[]{
                record.id,
                record.gameType,
                record.state,
                record.playerX,
                record.playerO,
                GameRecord.ipSummary(record),
                record.plateau.nombreCoups(),
                record.result,
                "Voir"
        };
    }

    public synchronized void updateStatsAfterGame(String playerX, String playerO, String resultType, String winnerName, String loserName) {
        PlayerStats sx = statsByName.computeIfAbsent(playerX, PlayerStats::new);
        PlayerStats so = statsByName.computeIfAbsent(playerO, PlayerStats::new);

        sx.games++;
        so.games++;

        if ("DRAW".equals(resultType)) {
            sx.draw++;
            so.draw++;
        } else {
            if (winnerName != null) {
                PlayerStats sw = statsByName.computeIfAbsent(winnerName, PlayerStats::new);
                sw.win++;
            }
            if (loserName != null) {
                PlayerStats sl = statsByName.computeIfAbsent(loserName, PlayerStats::new);
                sl.lose++;
            }
        }

        refreshClassificationTable();
        broadcastClassificationToClients();
    }

    /** Classe tous les joueurs ayant eu des stats (historique hors connexion serveur uniquement liste). */
    private synchronized List<PlayerStats> sortedAllStats() {
        List<PlayerStats> list = new ArrayList<>();
        for (PlayerStats ps : statsByName.values()) {
            list.add(ps.copy());
        }
        list.sort(Comparator.comparingInt(PlayerStats::difference).reversed()
                .thenComparingInt((PlayerStats ps) -> ps.tournamentsWon).reversed()
                .thenComparing(ps -> ps.player.toLowerCase(Locale.ROOT)));
        return list;
    }

    public void refreshClassificationTable() {
        List<PlayerStats> sorted = sortedAllStats();
        SwingUtilities.invokeLater(() -> {
            classModel.setRowCount(0);
            int rank = 1;
            for (PlayerStats ps : sorted) {
                classModel.addRow(new Object[]{
                        rank++, ps.player, ps.games, ps.win, ps.lose, ps.draw, ps.tournamentsWon, ps.difference()});
            }
        });
    }

    public void broadcastClassificationToClients() {
        String payload = buildClassificationPayload();
        for (PlayerHandler player : playersByName.values()) {
            player.send("CLASSIFICATION:" + payload);
        }
    }

    public String buildClassificationPayload() {
        StringBuilder sb = new StringBuilder();
        List<PlayerStats> sorted = sortedAllStats();
        for (int i = 0; i < sorted.size(); i++) {
            PlayerStats ps = sorted.get(i);
            if (i > 0) {
                sb.append(";");
            }
            sb.append(ps.player).append(",")
                    .append(ps.games).append(",")
                    .append(ps.win).append(",")
                    .append(ps.lose).append(",")
                    .append(ps.draw).append(",")
                    .append(ps.tournamentsWon).append(",")
                    .append(ps.difference());
        }
        return sb.toString();
    }

    public void showDetailsFor(int gameId) {
        GameRecord record = gameRecords.get(gameId);
        if (record == null) {
            JOptionPane.showMessageDialog(this, "Aucune donnée pour cette partie.");
            return;
        }
        JFrame frame = new JFrame("Partie " + gameId + " — plateau");
        frame.setSize(360, 420);
        frame.setLayout(new BorderLayout());
        frame.setLocationRelativeTo(this);
        ((JPanel) frame.getContentPane()).setOpaque(true);
        frame.getContentPane().setBackground(XOTheme.BLACK_BLUE);

        JPanel grid = new JPanel(new GridLayout(3, 3));
        grid.setOpaque(true);
        grid.setBackground(XOTheme.BLACK_BLUE);
        String[][] board = record.plateau.copieGrille();
        Plateau.LigneGagnante ligne = record.plateau.getGagnantEtLigneGagnante();
        Set<String> winning = Collections.emptySet();
        if (ligne != null) {
            winning = new LinkedHashSet<>();
            for (int[] c : ligne.cellules) {
                winning.add(c[0] + "," + c[1]);
            }
        }

        Border winBorder = XOTheme.paddedLineBorder(XOTheme.ACCENT_BRIGHT, 3, 2);
        Border normalBorder = XOTheme.paddedLineBorder(XOTheme.BORDER_BLUE, 1, 2);

        Color winBg = XOTheme.WIN_CELL_BG;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                JLabel label = new JLabel(board[i][j], SwingConstants.CENTER);
                label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
                boolean isWinCell = winning.contains(i + "," + j);
                label.setBorder(isWinCell ? winBorder : normalBorder);
                label.setOpaque(true);
                label.setForeground(isWinCell ? Color.WHITE : XOTheme.TEXT_BRIGHT);
                label.setBackground(isWinCell ? winBg : XOTheme.CELL_FACE);
                grid.add(label);
            }
        }

        JTextArea details = new JTextArea(
                "Type : " + record.gameType + "\n"
                        + "État : " + record.state + "\n"
                        + "X : " + record.playerX + " (" + record.ipX + ":" + record.portX + ")\n"
                        + "O : " + record.playerO + " (" + record.ipO + ":" + record.portO + ")\n"
                        + "Coups : " + record.plateau.nombreCoups() + "\n"
                        + "Résultat : " + record.result + "\n"
        );
        details.setEditable(false);
        details.setOpaque(true);
        details.setForeground(XOTheme.TEXT_BRIGHT);
        details.setBackground(XOTheme.NAVY_PANEL);
        details.setBorder(XOTheme.paddedLineBorder(XOTheme.BORDER_BLUE, 1, 10));

        frame.add(grid, BorderLayout.CENTER);
        frame.add(details, BorderLayout.SOUTH);
        frame.setVisible(true);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            XOTheme.installOptionPaneHints();
            new ServeurXO().setVisible(true);
        });
    }

    enum PlayerStatus {
        CONNECTED, WAITING, IN_GAME
    }

    static class FinishedGameSnapshot {
        final long whenMs;
        final int gameId;
        final String state;
        final String playerX;
        final String playerO;
        final String ipsSummary;
        final int moveCount;
        final String resultSummary;

        FinishedGameSnapshot(long whenMs, int gameId, String state, String playerX, String playerO,
                             String ipsSummary, int moveCount, String resultSummary) {
            this.whenMs = whenMs;
            this.gameId = gameId;
            this.state = state;
            this.playerX = playerX;
            this.playerO = playerO;
            this.ipsSummary = ipsSummary;
            this.moveCount = moveCount;
            this.resultSummary = resultSummary;
        }
    }

    static class PlayerStats {
        final String player;
        int games;
        int win;
        int lose;
        int draw;
        int tournamentsWon;

        PlayerStats(String player) {
            this.player = player;
        }

        int difference() {
            return win - lose;
        }

        PlayerStats copy() {
            PlayerStats cp = new PlayerStats(player);
            cp.games = games;
            cp.win = win;
            cp.lose = lose;
            cp.draw = draw;
            cp.tournamentsWon = tournamentsWon;
            return cp;
        }
    }

    static class GameRecord {
        final int id;
        volatile String state;
        final String playerX;
        final String playerO;
        volatile String result;
        final Plateau plateau;
        volatile String gameType = "Normal";
        volatile int linkedTournamentId = -1;
        volatile int linkedRound = -1;
        volatile int linkedSlot = -1;
        String ipX = "—";
        int portX;
        String ipO = "—";
        int portO;

        GameRecord(int id, String state, String playerX, String playerO, String result, Plateau plateau) {
            this.id = id;
            this.state = state;
            this.playerX = playerX;
            this.playerO = playerO;
            this.result = result;
            this.plateau = plateau;
        }

        static String ipSummary(GameRecord r) {
            String sx = r.playerX.isEmpty() ? "—" : (r.ipX + ":" + (r.portX == 0 ? "—" : r.portX));
            String so = r.playerO.isEmpty() ? "—" : (r.ipO + ":" + (r.portO == 0 ? "—" : r.portO));
            return sx + " | " + so;
        }
    }

    static class PlayerHandler implements Runnable {
        private final Socket socket;
        private final ServeurXO server;
        private final BufferedReader in;
        private final PrintWriter out;
        private final LinkedBlockingQueue<String> gameQueue = new LinkedBlockingQueue<>();

        private volatile boolean connected = true;
        private volatile PlayerStatus status = PlayerStatus.CONNECTED;
        private volatile String postChoice;
        private String name;
        private int waitingRecordId = -1;
        volatile boolean tournamentQueued;
        private final String remoteIp;
        private final int clientPort;


        PlayerHandler(Socket socket, ServeurXO server) throws IOException {
            this.socket = socket;
            this.server = server;
            this.remoteIp = socket.getInetAddress() == null ? "?" : socket.getInetAddress().getHostAddress();
            this.clientPort = socket.getPort();
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.out = new PrintWriter(socket.getOutputStream(), true);
        }

        String remoteHost() {
            return remoteIp;
        }

        int remotePort() {
            return clientPort;
        }

        @Override
        public void run() {
            try {
                send("ASK_NAME");
                String line = in.readLine();
                if (line == null || !line.startsWith("NAME:")) {
                    close();
                    return;
                }
                String finalName = server.registerPlayer(this, line.substring(5));
                send("WELCOME:" + finalName);
                send("PROFILE:" + remoteIp + "|" + clientPort + "|connecté");
                send("INFO:Bienvenue. Jouer, Classification, ou Tournoi (file depuis le bouton Tournoi).");

                send("CLASSIFICATION:" + server.buildClassificationPayload());
                server.getTournamentManager().notifyPlayerQueueState(this);

                while (connected) {
                    String msg = in.readLine();
                    if (msg == null) {
                        break;
                    }
                    handleMessage(msg.trim());
                }
            } catch (IOException e) {
                server.appendLog("ERROR", "Lecture réseau " + describe() + " : " + e.getMessage());
            } finally {
                close();
            }
        }

        private String describe() {
            return (name != null ? name : "?") + "@" + remoteIp;
        }

        private void handleMessage(String msg) {
            if (msg.isEmpty()) {
                return;
            }
            if ("POST:REPLAY".equals(msg) || "POST:NO_REPLAY".equals(msg)) {
                postChoice = msg;
                server.appendLog("GAME", describe() + " choix après partie : " + msg);
                return;
            }
            if ("MENU:LOGOUT".equals(msg) || "POST:EXIT".equals(msg)) {
                if (status == PlayerStatus.IN_GAME) {
                    gameQueue.offer("QUIT");
                } else {
                    send("BYE");
                    close();
                }
                return;
            }

            if (status == PlayerStatus.IN_GAME) {
                gameQueue.offer(msg);
                return;
            }

            if ("MENU:PLAY".equals(msg)) {
                if (tournamentQueued) {
                    send("INFO:Quittez la file tournoi (« Quitter file tournoi ») pour jouer en partie classique.");
                    return;
                }
                server.queueForRandomGame(this);
            } else if ("MENU:JOIN_TOURNAMENT".equals(msg)) {
                server.getTournamentManager().joinQueue(this);
            } else if ("MENU:LEAVE_TOURNAMENT_QUEUE".equals(msg)) {
                server.getTournamentManager().leaveQueue(this);
                send("PROFILE:" + remoteHost() + "|" + clientPort + "|connecté");
            } else if ("MENU:CLASSIFICATION".equals(msg) || "GET_CLASSIFICATION".equals(msg)) {
                send("CLASSIFICATION:" + server.buildClassificationPayload());
            } else if (msg.startsWith("REQUEST_GAME:")) {
                server.requestChallenge(this, msg.substring("REQUEST_GAME:".length()).trim());
            } else if (msg.startsWith("CHALLENGE_RESPONSE:")) {
                String payload = msg.substring("CHALLENGE_RESPONSE:".length());
                int cut = payload.lastIndexOf(':');
                if (cut > 0) {
                    String fromName = payload.substring(0, cut);
                    boolean accept = "ACCEPT".equalsIgnoreCase(payload.substring(cut + 1));
                    server.answerChallenge(this, fromName, accept);
                }
            } else if ("QUIT".equals(msg)) {
                send("BYE");
                close();
            }
        }

        String takeGameCommand() throws InterruptedException {
            return gameQueue.take();
        }

        public PlayerStatus getStatus() {
            return status;
        }

        public String getPlayerName() {
            return name;
        }

        void interruptGameWaiting() {
            gameQueue.offer("ADMIN_STOP");
        }

        String waitPostChoice() {
            while (connected) {
                if (postChoice != null) {
                    String tmp = postChoice;
                    postChoice = null;
                    return tmp;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return null;
        }

        void send(String msg) {
            out.println(msg);
        }

        void clearGameQueue() {
            gameQueue.clear();
        }

        boolean isConnected() {
            return connected && !socket.isClosed();
        }

        boolean isAvailable() {
            return isConnected() && status != PlayerStatus.IN_GAME;
        }

        void close() {
            if (!connected) {
                return;
            }
            connected = false;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            server.disconnectPlayer(this);
        }
    }

    static class GameSession implements Runnable {
        private final GameRecord record;
        private final PlayerHandler playerX;
        private final PlayerHandler playerO;
        private final ServeurXO server;
        private volatile boolean adminStopped;
        private final boolean tournamentMatch;

        GameSession(GameRecord record, PlayerHandler playerX, PlayerHandler playerO,
                    ServeurXO server, boolean tournamentMatch) {
            this.record = record;
            this.playerX = playerX;
            this.playerO = playerO;
            this.server = server;
            this.tournamentMatch = tournamentMatch;
        }

        void forceStopByAdmin() {
            adminStopped = true;
            playerX.interruptGameWaiting();
            playerO.interruptGameWaiting();
        }

        @Override
        public void run() {
            server.registerActiveGame(record.id, this);
            try {
                runGameLoop();
            } finally {
                server.unregisterActiveGame(record.id);
            }
        }

        private void runGameLoop() {
            playerX.send("START:" + record.id + ":X:" + playerO.name);
            playerO.send("START:" + record.id + ":O:" + playerX.name);

            PlayerHandler current = playerX;
            PlayerHandler other = playerO;
            String currentSymbol = "X";

            while (true) {
                if (adminStopped) {
                    handleAdminStopMidGame();
                    return;
                }

                current.send("YOUR_TURN");
                String cmd;
                try {
                    cmd = current.takeGameCommand();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (adminStopped || "ADMIN_STOP".equals(cmd)) {
                    handleAdminStopMidGame();
                    return;
                }

                if (cmd == null || "QUIT".equals(cmd) || !current.isConnected()) {
                    endWithQuit(current, other);
                    return;
                }

                if (cmd.startsWith("CHAT:")) {
                    String peerMsg = sanitizeChat(cmd.substring(5));
                    if (!peerMsg.isEmpty()) {
                        other.send("CHAT_PEER:" + current.name + ":" + peerMsg);
                        server.appendLog("CHAT", "Partie " + record.id + " " + current.name + ": " + peerMsg);
                    }
                    continue;
                }

                if (!cmd.startsWith("MOVE:")) {
                    current.send("INVALID");
                    continue;
                }
                String[] parts = cmd.substring(5).split(",");
                if (parts.length != 2) {
                    current.send("INVALID");
                    continue;
                }
                int row;
                int col;
                try {
                    row = Integer.parseInt(parts[0].trim());
                    col = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException e) {
                    current.send("INVALID");
                    continue;
                }

                if (!record.plateau.jouerCoup(row, col, currentSymbol)) {
                    current.send("INVALID");
                    continue;
                }

                playerX.send("MOVE:" + row + "," + col + "," + currentSymbol);
                playerO.send("MOVE:" + row + "," + col + "," + currentSymbol);
                server.updateGameRecord(record);
                server.appendLog("MOVE", "Partie " + record.id + " coup " + current.name + " (" + row + "," + col + ") "
                        + currentSymbol);

                Plateau.LigneGagnante ligne = record.plateau.getGagnantEtLigneGagnante();
                if (ligne != null) {
                    String winPayload = ligne.cellules[0][0] + "," + ligne.cellules[0][1] + ";"
                            + ligne.cellules[1][0] + "," + ligne.cellules[1][1] + ";"
                            + ligne.cellules[2][0] + "," + ligne.cellules[2][1];
                    playerX.send("WINLINE:" + winPayload);
                    playerO.send("WINLINE:" + winPayload);
                }

                String winnerSymbol = record.plateau.getGagnant();
                if (winnerSymbol != null) {
                    String winnerName = "X".equals(winnerSymbol) ? playerX.name : playerO.name;
                    String loserName = winnerName.equals(playerX.name) ? playerO.name : playerX.name;
                    PlayerHandler winnerH = winnerName.equals(playerX.name) ? playerX : playerO;
                    PlayerHandler loserH = winnerH == playerX ? playerO : playerX;
                    record.state = "terminée";
                    record.result = winnerName;
                    server.updateGameRecord(record);
                    server.updateStatsAfterGame(playerX.name, playerO.name, "WIN", winnerName, loserName);
                    playerX.send("END:WINNER:" + winnerName);
                    playerO.send("END:WINNER:" + winnerName);
                    server.recordFinishedGameSnapshot(record);
                    if (tournamentMatch) {
                        advanceTournament(winnerH, loserH);
                    } else {
                        postGameChoice();
                    }
                    return;
                }

                if (record.plateau.estPlein()) {
                    if (tournamentMatch) {
                        record.plateau.reset();
                        record.state = "en cours";
                        record.result = "";
                        server.updateGameRecord(record);
                        playerX.send("TOURNAMENT_DRAW_RESET:" + record.id);
                        playerO.send("TOURNAMENT_DRAW_RESET:" + record.id);
                        current = playerX;
                        other = playerO;
                        currentSymbol = "X";
                        continue;
                    }
                    record.state = "terminée";
                    record.result = "nulle";
                    server.updateGameRecord(record);
                    server.updateStatsAfterGame(playerX.name, playerO.name, "DRAW", null, null);
                    playerX.send("END:DRAW");
                    playerO.send("END:DRAW");
                    server.recordFinishedGameSnapshot(record);
                    postGameChoice();
                    return;
                }

                PlayerHandler tmp = current;
                current = other;
                other = tmp;
                currentSymbol = "X".equals(currentSymbol) ? "O" : "X";
            }
        }

        private void advanceTournament(PlayerHandler winnerH, PlayerHandler loserH) {
            server.finishGameForPlayers(playerX, playerO);
            TournamentManager.BracketMatch bm = server.getTournamentManager().findBracketMatch(
                    record.linkedTournamentId, record.linkedRound, record.linkedSlot);
            if (bm != null) {
                server.getTournamentManager().onMatchResolved(bm, winnerH, loserH);
            }
        }

        private static String sanitizeChat(String s) {
            if (s.length() > 280) {
                s = s.substring(0, 280);
            }
            return s.replace('\n', ' ').replace('\r', ' ');
        }

        private void handleAdminStopMidGame() {
            if (tournamentMatch) {
                server.finishGameForPlayers(playerX, playerO);
                server.getTournamentManager().cancelActiveDueToAdmin();
                return;
            }
            record.state = "fermée (admin)";
            record.result = "—";
            server.updateGameRecord(record);
            server.appendLog("ADMIN", "Partie " + record.id + " close par l’admin.");
            playerX.send("END:ADMIN:Partie fermée par l administrateur.");
            playerO.send("END:ADMIN:Partie fermée par l administrateur.");
            server.finishGameForPlayers(playerX, playerO);
            server.recordFinishedGameSnapshot(record);
        }

        private void endWithQuit(PlayerHandler quitter, PlayerHandler winner) {
            record.state = "abandon";
            record.result = winner.name;
            server.updateGameRecord(record);
            server.updateStatsAfterGame(playerX.name, playerO.name, "WIN", winner.name, quitter.name);
            playerX.send("END:QUIT:" + winner.name + ":" + quitter.name);
            playerO.send("END:QUIT:" + winner.name + ":" + quitter.name);
            server.recordFinishedGameSnapshot(record);
            if (tournamentMatch) {
                advanceTournament(winner, quitter);
            } else {
                postGameChoice();
            }
        }

        private void postGameChoice() {
            server.finishGameForPlayers(playerX, playerO);

            if (!playerX.isConnected() || !playerO.isConnected()) {
                return;
            }
            playerX.send("CHOOSE:REPLAY_OR_EXIT");
            playerO.send("CHOOSE:REPLAY_OR_EXIT");

            String choiceX = playerX.waitPostChoice();
            String choiceO = playerO.waitPostChoice();

            boolean replayX = "POST:REPLAY".equals(choiceX);
            boolean replayO = "POST:REPLAY".equals(choiceO);

            if (replayX && replayO) {
                server.startGameBetween(playerX, playerO);
                return;
            }

            playerX.send("RETURN_TO_MENU");
            playerO.send("RETURN_TO_MENU");

            playerX.clearGameQueue();
            playerO.clearGameQueue();

            if (replayX) {
                server.queueForRandomGame(playerX);
            }

            if (replayO) {
                server.queueForRandomGame(playerO);
            }

            playerX.send("CLASSIFICATION:" + server.buildClassificationPayload());
            playerO.send("CLASSIFICATION:" + server.buildClassificationPayload());

            playerX.send("PROFILE:" + playerX.remoteHost() + "|" + playerX.remotePort() + "|connecté");
            playerO.send("PROFILE:" + playerO.remoteHost() + "|" + playerO.remotePort() + "|connecté");
        }
    }

    static class DetailButtonRenderer extends JButton implements javax.swing.table.TableCellRenderer {
        DetailButtonRenderer() {
            setOpaque(true);
            setText("Voir");
            XOTheme.styleEmbeddedTableButton(this);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            XOTheme.styleEmbeddedTableButton(this);
            if (isSelected) {
                setBackground(XOTheme.ACCENT_BLUE);
            }
            return this;
        }
    }

    static class DetailButtonEditor extends DefaultCellEditor {
        private final JButton button;
        private final ServeurXO server;
        private final JTable table;
        private int selectedRow = -1;

        DetailButtonEditor(JCheckBox checkBox, ServeurXO server, JTable table) {
            super(checkBox);
            this.server = server;
            this.table = table;
            this.button = new JButton("Voir");
            XOTheme.styleEmbeddedTableButton(this.button);
            this.button.addActionListener(e -> fireEditingStopped());
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            selectedRow = row;
            return button;
        }

        @Override
        public Object getCellEditorValue() {
            if (selectedRow >= 0) {
                Object value = table.getValueAt(selectedRow, 0);
                if (value instanceof Integer) {
                    int gameId = (Integer) value;
                    SwingUtilities.invokeLater(() -> server.showDetailsFor(gameId));
                }
            }
            return "Voir";
        }
    }
}
