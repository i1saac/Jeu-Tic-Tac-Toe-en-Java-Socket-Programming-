import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** Fenêtre admin : vue bracket + historique des champions. */
public class TournamentBracketWindow extends JFrame {
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ServeurXO server;
    private final TournamentManager manager;
    private final JPanel bracketArea = new JPanel();
    private final DefaultTableModel historyModel;

    public TournamentBracketWindow(ServeurXO srv, TournamentManager mgr) {
        super("Tournois — tableau & historique");
        this.server = srv;
        this.manager = mgr;
        mgr.setDashboard(this);

        setSize(1000, 620);
        setLocationRelativeTo(srv);

        XOTheme.stylePanelFilled(bracketArea, XOTheme.NAVY_PANEL);
        bracketArea.setLayout(new FlowLayout(FlowLayout.LEFT, 12, 16));

        JButton refresh = new JButton("Rafraîchir");
        XOTheme.styleSecondaryButton(refresh);
        refresh.addActionListener(e -> refreshFromServer());

        String[] hc = {"ID", "Date", "Taille", "Champion", "IP champion", "Participants"};
        historyModel = new DefaultTableModel(hc, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        JTable ht = new JTable(historyModel);
        XOTheme.styleTable(ht);
        JScrollPane hsp = new JScrollPane(ht);
        XOTheme.styleScrollPane(hsp, XOTheme.CELL_FACE);

        JPanel top = new JPanel(new BorderLayout());
        top.add(refresh, BorderLayout.WEST);
        JPanel splitTop = new JPanel(new BorderLayout());
        splitTop.add(new JLabel("Structure actuelle (colonnes = tours)", SwingConstants.LEFT), BorderLayout.NORTH);
        splitTop.add(new JScrollPane(bracketArea), BorderLayout.CENTER);

        JSplitPane sp = new JSplitPane(JSplitPane.VERTICAL_SPLIT, splitTop, hsp);
        sp.setResizeWeight(0.55);
        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(sp, BorderLayout.CENTER);

        refreshFromServer();
    }

    public void refreshFromServer() {
        TournamentManager.ActiveTournament cur = manager.getCurrentForView();
        bracketArea.removeAll();
        bracketArea.setLayout(new FlowLayout(FlowLayout.LEFT, 12, 24));

        if (cur != null) {
            List<List<Map<String, String>>> vm = cur.viewModel();
            for (int r = 0; r < vm.size(); r++) {
                JPanel col = new JPanel();
                col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
                col.setOpaque(false);
                JLabel head = new JLabel("Tour " + (r + 1));
                head.setForeground(XOTheme.ACCENT_BRIGHT);
                col.add(head);
                for (Map<String, String> cell : vm.get(r)) {
                    JPanel box = new JPanel(new GridLayout(4, 1, 2, 2));
                    XOTheme.stylePanelFilled(box, XOTheme.CELL_FACE);
                    box.add(new JLabel(cell.getOrDefault("A", "—")));
                    box.add(new JLabel("vs " + cell.getOrDefault("B", "—")));
                    JLabel st = new JLabel(cell.getOrDefault("stat", ""));
                    st.setForeground(XOTheme.TEXT_DIM);
                    box.add(st);
                    col.add(Box.createVerticalStrut(6));
                    col.add(box);
                }
                bracketArea.add(col);
            }
        } else {
            JLabel idle = new JLabel("Aucun tournoi en cours (file : " + manager.getQueueCount()
                    + "/" + manager.getConfiguredBracketSize() + ")");
            idle.setForeground(XOTheme.TEXT_BRIGHT);
            bracketArea.add(idle);
        }

        historyModel.setRowCount(0);
        for (TournamentHistory h : manager.getHistorySnapshot()) {
            historyModel.addRow(new Object[]{
                    h.getTournamentId(),
                    FMT.format(h.getFinishedAt()),
                    h.getBracketSize(),
                    h.getChampionName(),
                    h.getChampionIp(),
                    String.join(", ", h.getParticipants())
            });
        }

        bracketArea.revalidate();
        bracketArea.repaint();
    }
}
