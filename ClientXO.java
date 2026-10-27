import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class ClientXO extends JFrame {
    private final JButton[][] boutons = new JButton[3][3];
    private final BufferedReader in;
    private final PrintWriter out;
    private String symbole = "";
    private final String myName;
    private volatile boolean monTour = false;
    private volatile boolean inGame = false;
    private String pendingChallengeFrom;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel rootPanel = new JPanel(cardLayout);
    private final JLabel infoLabel = new JLabel("Connexion...", SwingConstants.CENTER);
    private final JTextField tfName = new JTextField(12);
    private final JTextField tfIpSelf = new JTextField(14);
    private final JTextField tfPortSelf = new JTextField(8);
    private final JTextField tfStatus = new JTextField(22);
    private final DefaultTableModel classModel;
    private final JTable classTable;

    private final JButton quitGameButton = new JButton("Quitter la partie");
    private final JButton playButton = new JButton("Jouer");
    private final JButton showClassificationButton = new JButton("Classification");
    private final JButton joinTournamentButton = new JButton("Tournoi");
    private final JButton leaveTournamentQueueButton = new JButton("Quitter file tournoi");
    private final JLabel lblTournamentQueue = new JLabel(" ", SwingConstants.LEFT);
    private final JButton refreshButton = new JButton("Actualiser classement");
    private final JButton backToMenuButton = new JButton("Retour");
    private final JButton exitConnectionButton = new JButton("Sortir / déconnexion");

    private final JTextArea chatArea = new JTextArea(4, 30);
    private final JTextField chatInput = new JTextField(26);
    private final JButton sendChatButton = new JButton("Envoyer");

    private final Border[][] defaultBorders = new Border[3][3];
    private final Color[][] defaultBg = new Color[3][3];

    public ClientXO(String hote, int port, String myName) throws IOException {
        Socket socket = new Socket(hote, port);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out = new PrintWriter(socket.getOutputStream(), true);
        this.myName = myName;

        setTitle("Client XO");
        setSize(760, 640);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        JPanel menuPanel = new JPanel(new BorderLayout(8, 8));
        menuPanel.setBorder(XOTheme.framedSection("Menu"));

        JPanel identity = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 4, 2, 4);
        gc.anchor = GridBagConstraints.WEST;

        gc.gridy = 0;
        gc.gridx = 0;
        identity.add(new JLabel("Nom :"), gc);
        gc.gridx = 1;
        tfName.setText(myName);
        tfName.setEditable(false);
        identity.add(tfName, gc);

        gc.gridy = 1;
        gc.gridx = 0;
        identity.add(new JLabel("IP (client vue par serveur) :"), gc);
        gc.gridx = 1;
        tfIpSelf.setEditable(false);
        identity.add(tfIpSelf, gc);

        gc.gridy = 2;
        gc.gridx = 0;
        identity.add(new JLabel("Port local :"), gc);
        gc.gridx = 1;
        tfPortSelf.setEditable(false);
        identity.add(tfPortSelf, gc);

        gc.gridy = 3;
        gc.gridx = 0;
        identity.add(new JLabel("Statut / erreurs :"), gc);
        gc.gridx = 1;
        tfStatus.setEditable(false);
        identity.add(tfStatus, gc);

        JPanel menuButtons = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 10));
        menuButtons.add(playButton);
        menuButtons.add(showClassificationButton);
        menuButtons.add(joinTournamentButton);
        menuButtons.add(leaveTournamentQueueButton);
        menuButtons.add(exitConnectionButton);

        JPanel tournamentRow = new JPanel(new BorderLayout());
        tournamentRow.setOpaque(false);
        tournamentRow.add(lblTournamentQueue, BorderLayout.CENTER);

        JPanel menuSouth = new JPanel(new BorderLayout(0, 6));
        menuSouth.add(tournamentRow, BorderLayout.NORTH);
        menuSouth.add(menuButtons, BorderLayout.SOUTH);

        menuPanel.add(infoLabel, BorderLayout.NORTH);
        menuPanel.add(identity, BorderLayout.CENTER);
        menuPanel.add(menuSouth, BorderLayout.SOUTH);

        playButton.addActionListener(e -> out.println("MENU:PLAY"));
        joinTournamentButton.addActionListener(e -> out.println("MENU:JOIN_TOURNAMENT"));
        leaveTournamentQueueButton.addActionListener(e -> {
            out.println("MENU:LEAVE_TOURNAMENT_QUEUE");
            setTournamentQueueLabel("");
        });
        showClassificationButton.addActionListener(e -> {
            out.println("MENU:CLASSIFICATION");
            cardLayout.show(rootPanel, "classification");
        });
        exitConnectionButton.addActionListener(e -> {
            out.println("MENU:LOGOUT");
            JOptionPane.showMessageDialog(this, "Déconnexion envoyée au serveur.");
            System.exit(0);
        });

        JPanel gamePanel = new JPanel(new BorderLayout());
        JPanel boardPanel = new JPanel(new GridLayout(3, 3));

        Border thin = XOTheme.plateauCellBorder();
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                JButton btn = new JButton("");
                btn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 56));
                int x = i, y = j;
                defaultBorders[i][j] = thin;
                defaultBg[i][j] = XOTheme.CELL_FACE;
                btn.setOpaque(true);
                btn.setBorder(thin);
                btn.setBackground(XOTheme.CELL_FACE);
                btn.setForeground(XOTheme.ACCENT_BRIGHT);
                btn.setFocusPainted(false);

                btn.addActionListener(e -> {
                    if (monTour && btn.getText().equals("")) {
                        out.println("MOVE:" + x + "," + y);
                    }
                });
                boutons[i][j] = btn;
                boardPanel.add(btn);
            }
        }
        gamePanel.setBorder(XOTheme.framedSection("Plateau"));

        JPanel boardWrap = new JPanel(new BorderLayout());
        boardWrap.setOpaque(true);
        boardWrap.setBackground(XOTheme.BLACK_BLUE);
        boardWrap.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        boardPanel.setOpaque(true);
        boardPanel.setBackground(XOTheme.BLACK_BLUE);
        boardWrap.add(boardPanel, BorderLayout.CENTER);
        gamePanel.add(boardWrap, BorderLayout.CENTER);

        JPanel chatPanel = new JPanel(new BorderLayout());
        chatPanel.setOpaque(false);
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        JScrollPane chatScroll = new JScrollPane(chatArea);
        XOTheme.styleScrollPane(chatScroll, XOTheme.LOG_BG);
        chatPanel.add(chatScroll, BorderLayout.CENTER);
        JPanel chatLine = new JPanel(new FlowLayout(FlowLayout.LEFT));
        chatLine.setOpaque(false);
        chatLine.add(chatInput);
        chatLine.add(sendChatButton);
        chatPanel.add(chatLine, BorderLayout.SOUTH);

        Action sendChatAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!inGame) {
                    return;
                }
                String t = chatInput.getText().trim();
                if (t.isEmpty()) {
                    return;
                }
                out.println("CHAT:" + t);
                chatArea.append("(moi) " + t + "\n");
                chatInput.setText("");
                chatArea.setCaretPosition(chatArea.getDocument().getLength());
            }
        };
        sendChatButton.addActionListener(sendChatAction);
        chatInput.addActionListener(sendChatAction);

        JPanel southGame = new JPanel(new BorderLayout());
        southGame.setOpaque(true);
        southGame.setBackground(XOTheme.NAVY_PANEL);
        southGame.setBorder(XOTheme.paddedLineBorder(XOTheme.BORDER_BLUE, 1, 6));
        southGame.add(quitGameButton, BorderLayout.NORTH);
        southGame.add(chatPanel, BorderLayout.CENTER);
        gamePanel.add(southGame, BorderLayout.SOUTH);

        quitGameButton.addActionListener(e -> {
            if (inGame) {
                out.println("QUIT");
                monTour = false;
                tfStatus.setText("Abandon envoyé...");
            }
        });

        String[] cols = {"#", "Joueur", "Parties", "Gagnées", "Perdues", "Nulles", "Tournois", "Diff.", "Action"};
        classModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 8;
            }
        };
        classTable = new JTable(classModel);
        classTable.getColumn("Action").setCellRenderer(new MakeGameButtonRenderer());
        classTable.getColumn("Action").setCellEditor(new MakeGameButtonEditor(new JCheckBox(), classTable, this::onMakeGamePressed));

        JPanel classPanel = new JPanel(new BorderLayout());
        classPanel.setBorder(XOTheme.framedSection("Classement"));
        JPanel classTop = new JPanel(new FlowLayout(FlowLayout.LEFT));
        classTop.add(refreshButton);
        classTop.add(backToMenuButton);
        classPanel.add(classTop, BorderLayout.NORTH);
        XOTheme.styleTable(classTable);
        JScrollPane classScroll = new JScrollPane(classTable);
        XOTheme.styleScrollPane(classScroll, XOTheme.CELL_FACE);
        classPanel.add(classScroll, BorderLayout.CENTER);

        refreshButton.addActionListener(e -> out.println("GET_CLASSIFICATION"));
        backToMenuButton.addActionListener(e -> cardLayout.show(rootPanel, "menu"));

        rootPanel.add(menuPanel, "menu");
        rootPanel.add(gamePanel, "game");
        rootPanel.add(classPanel, "classification");
        add(rootPanel, BorderLayout.CENTER);

        applyClientTheme(menuPanel, identity, menuButtons, tournamentRow, gamePanel, southGame,
                chatPanel, chatLine, classPanel, classTop);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                out.println("MENU:LOGOUT");
            }
        });

        setVisible(true);
        cardLayout.show(rootPanel, "menu");
        disableBoard();

        new Thread(this::jouer, "xo-read").start();
    }

    private void jouer() {
        try {
            while (true) {
                String ligne = in.readLine();
                if (ligne == null) break;

                if (ligne.startsWith("SYMBOLE:")) {
                    symbole = ligne.substring(8);

                } else if (ligne.startsWith("ASK_NAME")) {
                    out.println("NAME:" + myName);

                } else if (ligne.startsWith("WELCOME:")) {
                    String srvName = ligne.substring(8).trim();
                    SwingUtilities.invokeLater(() -> tfName.setText(srvName));

                } else if (ligne.startsWith("PROFILE:")) {
                    String[] p = ligne.substring("PROFILE:".length()).split("\\|");
                    SwingUtilities.invokeLater(() -> {
                        if (p.length >= 1) tfIpSelf.setText(p[0].trim());
                        if (p.length >= 2) tfPortSelf.setText(p[1].trim());
                        if (p.length >= 3) tfStatus.setText(p[2].trim());
                    });

                } else if (ligne.startsWith("WINLINE:")) {
                    SwingUtilities.invokeLater(() -> highlightWinLine(ligne.substring("WINLINE:".length())));

                } else if (ligne.startsWith("CHAT_PEER:")) {
                    SwingUtilities.invokeLater(() -> appendChatPeer(ligne.substring("CHAT_PEER:".length())));

                } else if (ligne.startsWith("GAME_MODE:")) {
                    String mode = ligne.substring("GAME_MODE:".length()).trim();
                    SwingUtilities.invokeLater(() ->
                            tfStatus.setText("Mode : " + mode));

                } else if (ligne.startsWith("TOURNAMENT_QUEUE:")) {
                    String rest = ligne.substring("TOURNAMENT_QUEUE:".length()).trim();
                    setTournamentQueueLabel("File tournoi : " + rest.replace("/", " / ") + " joueurs");

                } else if (ligne.startsWith("TOURNAMENT_WAIT_NEXT:")) {
                    String msg = ligne.substring("TOURNAMENT_WAIT_NEXT:".length());
                    SwingUtilities.invokeLater(() -> {
                        tfStatus.setText("Tournoi — " + msg);
                        infoLabel.setText("En attente du prochain tour...");
                        cardLayout.show(rootPanel, "menu");
                        disableBoard();
                    });
                    inGame = false;
                    monTour = false;

                } else if (ligne.startsWith("TOURNAMENT_ROUND_ADVANCED:")) {
                    SwingUtilities.invokeLater(() ->
                            tfStatus.setText("Tournoi — tour " + ligne.substring("TOURNAMENT_ROUND_ADVANCED:".length())));

                } else if (ligne.startsWith("TOURNAMENT_MATCH:")) {
                    SwingUtilities.invokeLater(() ->
                            tfStatus.setText("Tournoi — match " + ligne.substring("TOURNAMENT_MATCH:".length())));

                } else if (ligne.startsWith("TOURNAMENT_STARTED:")) {
                    SwingUtilities.invokeLater(() ->
                            infoLabel.setText("Tournoi démarré : " + ligne.substring("TOURNAMENT_STARTED:".length())));

                } else if (ligne.startsWith("TOURNAMENT_DRAW_RESET:")) {
                    SwingUtilities.invokeLater(() -> {
                        resetBoardBoard();
                        inGame = true;
                        monTour = "X".equals(symbole);
                        tfStatus.setText("Tournoi — match nul, nouvelle partie identique.");
                    });

                } else if (ligne.startsWith("TOURNAMENT_ELIMINATED")) {
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(this, "Vous êtes éliminé du tournoi."));
                    SwingUtilities.invokeLater(this::gotoMenuConnected);

                } else if (ligne.startsWith("TOURNAMENT_CANCELLED:")) {
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(this, ligne.substring("TOURNAMENT_CANCELLED:".length())));
                    SwingUtilities.invokeLater(() -> gotoMenuConnected());

                } else if (ligne.startsWith("TOURNAMENT_CHAMP:")) {
                    String champ = ligne.substring("TOURNAMENT_CHAMP:".length()).trim();
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(this, champ + " remporte le tournoi.", "Tournoi",
                                    JOptionPane.INFORMATION_MESSAGE));

                } else if (ligne.startsWith("TOURNAMENT_ACTIVE_INFO:")) {
                    SwingUtilities.invokeLater(() ->
                            infoLabel.setText("Tournoi actif #" + ligne.substring("TOURNAMENT_ACTIVE_INFO:".length())));

                } else if (ligne.equals("WAITING")) {
                    monTour = false;
                    inGame = false;
                    SwingUtilities.invokeLater(() -> {
                        disableBoard();
                        cardLayout.show(rootPanel, "menu");
                        tfStatus.setText("En attente d’un adversaire…");
                        infoLabel.setText("En recherche de partie...");
                    });

                } else if (ligne.startsWith("START:")) {
                    String[] parts = ligne.split(":");
                    symbole = parts[2];
                    String opponent = parts.length > 3 ? parts[3] : "Inconnu";
                    SwingUtilities.invokeLater(() -> {
                        chatArea.setText("");
                        resetBoardBoard();
                        inGame = true;
                        monTour = "X".equals(symbole);
                        tfStatus.setText("Partie en cours");
                        setTitle("Match " + parts[1] + " — " + myName + " (" + symbole + ") vs " + opponent);
                        cardLayout.show(rootPanel, "game");
                        if (monTour) {
                            JOptionPane.showMessageDialog(this, "À vous de jouer.");
                        }
                    });

                } else if (ligne.equals("YOUR_TURN")) {
                    monTour = true;
                    inGame = true;

                } else if (ligne.startsWith("COUP:")) {
                    String[] parts = ligne.substring(5).split(",");
                    int i = Integer.parseInt(parts[0]);
                    int j = Integer.parseInt(parts[1]);
                    String s = parts[2];
                    SwingUtilities.invokeLater(() -> {
                        JButton b = boutons[i][j];
                        b.setText(s);
                        b.setForeground(XOTheme.TEXT_BRIGHT);
                    });
                    monTour = false;

                } else if (ligne.startsWith("MOVE:")) {
                    String[] parts = ligne.substring(5).split(",");
                    int i = Integer.parseInt(parts[0]);
                    int j = Integer.parseInt(parts[1]);
                    String s = parts[2];
                    SwingUtilities.invokeLater(() -> {
                        JButton b = boutons[i][j];
                        b.setText(s);
                        b.setForeground(XOTheme.TEXT_BRIGHT);
                    });
                    if (!s.equals(symbole)) {
                        monTour = true;
                    }

                } else if (ligne.startsWith("GAGNE:")) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Joueur " + ligne.substring(6) + " gagne."));
                    SwingUtilities.invokeLater(this::clearWinHighlights);
                    SwingUtilities.invokeLater(() -> gotoMenuConnected());

                } else if (ligne.startsWith("END:ADMIN:")) {
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(this, ligne.substring("END:ADMIN:".length())));
                    SwingUtilities.invokeLater(this::clearWinHighlights);
                    SwingUtilities.invokeLater(() -> gotoMenuConnected());

                } else if (ligne.startsWith("END:WINNER:")) {
                    inGame = false;
                    monTour = false;
                    SwingUtilities.invokeLater(this::disableBoard);

                } else if (ligne.equals("NUL")) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Match nul."));
                    SwingUtilities.invokeLater(() -> gotoMenuConnected());

                } else if (ligne.equals("END:DRAW")) {
                    inGame = false;
                    monTour = false;
                    SwingUtilities.invokeLater(this::disableBoard);

                } else if (ligne.startsWith("END:QUIT:")) {
                    String[] parts = ligne.split(":");
                    inGame = false;
                    monTour = false;
                    SwingUtilities.invokeLater(() -> {
                        disableBoard();
                        String q = parts.length > 3 ? parts[3] : "?";
                        String w = parts.length > 2 ? parts[2] : "?";
                        JOptionPane.showMessageDialog(this, q + " a quitté. Vainqueur : " + w);
                        gotoMenuConnected();
                    });

                } else if (ligne.equals("CHOOSE:REPLAY_OR_EXIT")) {
                    SwingUtilities.invokeLater(this::askReplayDialogBlocking);

                } else if (ligne.equals("RETURN_TO_MENU")) {
                    SwingUtilities.invokeLater(() -> {
                        clearWinHighlights();
                        gotoMenuConnected();
                    });

                } else if (ligne.equals("BYE")) {
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(this, "Serveur a fermé la connexion."));
                    System.exit(0);

                } else if (ligne.startsWith("CLASSIFICATION:")) {
                    SwingUtilities.invokeLater(() -> updateClassificationTable(ligne.substring("CLASSIFICATION:".length())));

                } else if (ligne.startsWith("CHALLENGE_FROM:")) {
                    pendingChallengeFrom = ligne.substring("CHALLENGE_FROM:".length()).trim();
                    askChallengeResponse();

                } else if (ligne.startsWith("INFO:")) {
                    SwingUtilities.invokeLater(() ->
                            tfStatus.setText(ligne.substring(5)));

                } else if (ligne.equals("INVALID")) {
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(this, "Coup invalide."));
                }
            }
        } catch (IOException e) {
            SwingUtilities.invokeLater(() -> tfStatus.setText("Erreur réseau : " + e.getMessage()));
        }
    }

    private void askReplayDialogBlocking() {
        int rep = JOptionPane.showConfirmDialog(
                this,
                "Rejouer avec cet adversaire ?\n(Refus = retour au menu sans déconnexion du serveur.)",
                "Fin de partie",
                JOptionPane.YES_NO_OPTION
        );
        if (rep == JOptionPane.YES_OPTION) {
            out.println("POST:REPLAY");
            tfStatus.setText("En attente de l’acceptation adverse…");
        } else {
            out.println("POST:NO_REPLAY");
            tfStatus.setText("Retour au menu client.");
            gotoMenuConnected();
        }
    }

    private void gotoMenuConnected() {
        clearWinHighlights();
        inGame = false;
        monTour = false;
        disableBoard();
        cardLayout.show(rootPanel, "menu");
        infoLabel.setText("Menu — connecté au serveur.");
    }

    private void applyClientTheme(JPanel menuPanel, JPanel identity, JPanel menuButtons, JPanel tournamentRow,
                                  JPanel gamePanel, JPanel southGame, JPanel chatPanel,
                                  JPanel chatLine, JPanel classPanel, JPanel classTop) {
        getContentPane().setBackground(XOTheme.BLACK_BLUE);
        rootPanel.setOpaque(true);
        rootPanel.setBackground(XOTheme.BLACK_BLUE);

        XOTheme.stylePanelFilled(menuPanel, XOTheme.NAVY_PANEL);
        propagateLabelForeground(identity, XOTheme.TEXT_DIM);
        XOTheme.styleTextField(tfName);
        XOTheme.styleTextField(tfIpSelf);
        XOTheme.styleTextField(tfPortSelf);
        XOTheme.styleTextField(tfStatus);

        menuButtons.setOpaque(false);
        XOTheme.stylePrimaryButton(playButton);
        XOTheme.stylePrimaryButton(showClassificationButton);
        XOTheme.styleSecondaryButton(exitConnectionButton);
        XOTheme.stylePrimaryButton(joinTournamentButton);
        XOTheme.styleSecondaryButton(leaveTournamentQueueButton);
        tournamentRow.setOpaque(false);
        lblTournamentQueue.setForeground(XOTheme.ACCENT_BRIGHT);

        XOTheme.stylePanelFilled(gamePanel, XOTheme.NAVY_PANEL);
        XOTheme.styleSecondaryButton(quitGameButton);
        XOTheme.stylePrimaryButton(sendChatButton);
        XOTheme.styleTextField(chatInput);
        XOTheme.styleTextArea(chatArea, false);
        propagateLabelForeground(chatPanel, XOTheme.TEXT_DIM);

        XOTheme.stylePanelFilled(classPanel, XOTheme.NAVY_PANEL);
        classTop.setOpaque(false);
        XOTheme.styleSecondaryButton(refreshButton);
        XOTheme.styleSecondaryButton(backToMenuButton);

        propagateLabelForeground(menuPanel, XOTheme.TEXT_DIM);
        XOTheme.styleAccentTitle(infoLabel);

        for (Container c : new Container[]{chatLine, chatPanel}) {
            for (Component ch : c.getComponents()) {
                if (ch instanceof JLabel jl) {
                    XOTheme.styleLabel(jl);
                }
            }
        }
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                boutons[i][j].setContentAreaFilled(true);
            }
        }
    }

    private static void propagateLabelForeground(Container container, Color color) {
        for (Component ch : container.getComponents()) {
            if (ch instanceof JLabel jl) {
                jl.setForeground(color);
            }
            if (ch instanceof Container co) {
                propagateLabelForeground(co, color);
            }
        }
    }

    private void highlightWinLine(String payload) {
        clearWinHighlights();
        CompoundBorder combo = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(XOTheme.ACCENT_BRIGHT, 4),
                BorderFactory.createEmptyBorder(2, 2, 2, 2));
        Color hi = XOTheme.WIN_CELL_BG;
        for (String coord : payload.split(";")) {
            String[] rc = coord.split(",");
            if (rc.length != 2) continue;
            try {
                int r = Integer.parseInt(rc[0].trim());
                int c = Integer.parseInt(rc[1].trim());
                boutons[r][c].setBorder(combo);
                boutons[r][c].setBackground(hi);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void clearWinHighlights() {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                boutons[i][j].setBorder(defaultBorders[i][j]);
                boutons[i][j].setBackground(defaultBg[i][j]);
            }
        }
    }

    private void appendChatPeer(String raw) {
        int cut = raw.indexOf(':');
        if (cut < 0) {
            return;
        }
        String who = raw.substring(0, cut);
        String txt = raw.substring(cut + 1);
        chatArea.append(who + ": " + txt + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    private void askChallengeResponse() {
        SwingUtilities.invokeLater(() -> {
            int answer = JOptionPane.showConfirmDialog(
                    this,
                    pendingChallengeFrom + " veut jouer contre vous. Accepter ?",
                    "Défi",
                    JOptionPane.YES_NO_OPTION
            );
            String decision = answer == JOptionPane.YES_OPTION ? "ACCEPT" : "REFUSE";
            out.println("CHALLENGE_RESPONSE:" + pendingChallengeFrom + ":" + decision);
            pendingChallengeFrom = null;
        });
    }

    private void updateClassificationTable(String payload) {
        SwingUtilities.invokeLater(() -> {
            classModel.setRowCount(0);
            if (payload == null || payload.trim().isEmpty()) {
                return;
            }
            String[] rows = payload.split(";");
            for (int i = 0; i < rows.length; i++) {
                String row = rows[i].trim();
                if (row.isEmpty()) {
                    continue;
                }
                String[] p = row.split(",");
                if (p.length < 8) {
                    continue;
                }
                String player = p[0].trim();
                String tw = p.length > 6 ? p[6] : "0";
                String diff = p[p.length - 1];
                if (player.equals(tfName.getText())) {
                    classModel.addRow(new Object[]{i + 1, player, p[1], p[2], p[3], p[4], tw, diff, "Moi"});
                } else {
                    classModel.addRow(new Object[]{i + 1, player, p[1], p[2], p[3], p[4], tw, diff, "Défier"});
                }
            }
        });
    }

    private void onMakeGamePressed(int row) {
        if (row < 0 || row >= classModel.getRowCount()) {
            return;
        }
        String action = String.valueOf(classModel.getValueAt(row, 8));
        if (!"Défier".equals(action)) {
            return;
        }
        String targetPlayer = String.valueOf(classModel.getValueAt(row, 1));
        out.println("REQUEST_GAME:" + targetPlayer);
    }

    private void setTournamentQueueLabel(String txt) {
        SwingUtilities.invokeLater(() -> lblTournamentQueue.setText(txt));
    }

    private void resetBoardBoard() {
        clearWinHighlights();
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                JButton b = boutons[i][j];
                b.setText("");
                b.setForeground(XOTheme.ACCENT_BRIGHT);
                b.setEnabled(true);
            }
        }
    }

    private void disableBoard() {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                boutons[i][j].setEnabled(false);
            }
        }
    }

    public static void main(String[] args) throws IOException {
        XOTheme.installOptionPaneHints();
        String host = JOptionPane.showInputDialog(null, "Hôte du serveur :", "localhost");
        if (host == null || host.trim().isEmpty()) {
            host = "localhost";
        }
        String portText = JOptionPane.showInputDialog(null, "Port :", "1234");
        int port = Integer.parseInt(portText == null || portText.trim().isEmpty() ? "1234" : portText.trim());
        String name = JOptionPane.showInputDialog(null, "Votre nom :");
        if (name == null || name.trim().isEmpty()) {
            name = "Player";
        }
        new ClientXO(host, port, name.trim());
    }

    static class MakeGameButtonRenderer extends JButton implements javax.swing.table.TableCellRenderer {
        MakeGameButtonRenderer() {
            setOpaque(true);
            XOTheme.styleEmbeddedTableButton(this);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            setText(String.valueOf(value));
            XOTheme.styleEmbeddedTableButton(this);
            if (isSelected) {
                setBackground(XOTheme.ACCENT_BLUE);
            }
            return this;
        }
    }

    interface RowAction {
        void onAction(int row);
    }

    static class MakeGameButtonEditor extends DefaultCellEditor {
        private final JButton button;
        private final JTable table;
        private final RowAction action;
        private int selectedRow = -1;

        MakeGameButtonEditor(JCheckBox checkBox, JTable table, RowAction action) {
            super(checkBox);
            this.table = table;
            this.action = action;
            this.button = new JButton();
            XOTheme.styleEmbeddedTableButton(this.button);
            this.button.addActionListener(e -> fireEditingStopped());
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            selectedRow = row;
            button.setText(String.valueOf(value));
            return button;
        }

        @Override
        public Object getCellEditorValue() {
            if (selectedRow >= 0) {
                SwingUtilities.invokeLater(() -> action.onAction(selectedRow));
            }
            return table.getValueAt(selectedRow, 8);
        }
    }
}
