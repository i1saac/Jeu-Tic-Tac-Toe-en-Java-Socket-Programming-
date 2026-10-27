import java.awt.*;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;

/**
 * Thème rose / noir commun au client et au serveur.
 */
public final class XOTheme {
    private XOTheme() {
    }

    public static final Color BLACK_BLUE = new Color(12, 10, 18);
    public static final Color NAVY_PANEL = new Color(28, 18, 34);
    public static final Color NAVY_RAISED = new Color(40, 24, 46);
    public static final Color ACCENT_BLUE = new Color(222, 95, 180);
    public static final Color ACCENT_BRIGHT = new Color(255, 150, 220);
    public static final Color BORDER_BLUE = new Color(225, 105, 170);
    public static final Color TEXT_BRIGHT = new Color(245, 230, 245);
    public static final Color TEXT_DIM = new Color(190, 160, 190);
    /** Cellules du plateau ou table sombre */
    public static final Color CELL_FACE = new Color(28, 18, 36);
    public static final Color WIN_CELL_BG = new Color(220, 100, 190);
    public static final Color LOG_BG = new Color(12, 8, 16);

    public static void installOptionPaneHints() {
        UIManager.put("OptionPane.background", NAVY_PANEL);
        UIManager.put("OptionPane.messageForeground", TEXT_BRIGHT);
        UIManager.put("Panel.background", BLACK_BLUE);
    }

    public static Border paddedLineBorder(Color line, int thick, int pad) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(line, thick),
                BorderFactory.createEmptyBorder(pad, pad, pad, pad));
    }

    public static Border plateauCellBorder() {
        return paddedLineBorder(BORDER_BLUE, 2, 4);
    }

    public static void stylePrimaryButton(AbstractButton b) {
        b.setOpaque(true);
        b.setFocusPainted(false);
        b.setForeground(Color.WHITE);
        b.setBackground(ACCENT_BLUE);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT_BRIGHT, 1),
                BorderFactory.createEmptyBorder(8, 16, 8, 16)));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    /** Bouton secondaire (même palette, fond plus sombre). */
    public static void styleSecondaryButton(AbstractButton b) {
        b.setOpaque(true);
        b.setFocusPainted(false);
        b.setForeground(TEXT_BRIGHT);
        b.setBackground(NAVY_RAISED);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_BLUE),
                BorderFactory.createEmptyBorder(8, 16, 8, 16)));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    public static void styleTextField(JTextField tf) {
        tf.setOpaque(true);
        tf.setBackground(BLACK_BLUE);
        tf.setForeground(TEXT_BRIGHT);
        tf.setCaretColor(ACCENT_BRIGHT);
        tf.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_BLUE),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
    }

    public static void styleTextArea(JTextArea ta, boolean editable) {
        ta.setOpaque(true);
        ta.setBackground(LOG_BG);
        ta.setForeground(TEXT_BRIGHT);
        ta.setCaretColor(ACCENT_BRIGHT);
        ta.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_BLUE),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
    }

    public static void styleLabel(JLabel lb) {
        lb.setForeground(TEXT_BRIGHT);
    }

    /** Titre sous-ligne sous le bandeau. */
    public static void styleAccentTitle(JLabel title) {
        title.setForeground(ACCENT_BRIGHT);
        Font f = title.getFont();
        title.setFont(f.deriveFont(Font.BOLD, f.getSize2D() + 2f));
    }

    public static void styleScrollPane(JScrollPane scroll, Color viewportBg) {
        scroll.setBorder(BorderFactory.createLineBorder(BORDER_BLUE));
        scroll.getViewport().setBackground(viewportBg);
        scroll.getViewport().setOpaque(true);
    }

    public static void styleTable(JTable table) {
        table.setOpaque(true);
        table.setBackground(CELL_FACE);
        table.setForeground(TEXT_BRIGHT);
        table.setSelectionBackground(ACCENT_BLUE);
        table.setSelectionForeground(Color.WHITE);
        table.setGridColor(BORDER_BLUE);
        table.setFillsViewportHeight(true);

        DefaultTableCellRenderer cell = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean selected, boolean focus,
                                                          int row, int col) {
                Component comp = super.getTableCellRendererComponent(t, v, selected, focus, row, col);
                comp.setBackground(selected ? table.getSelectionBackground() : CELL_FACE);
                comp.setForeground(TEXT_BRIGHT);
                return comp;
            }
        };
        cell.setHorizontalAlignment(SwingConstants.LEADING);
        table.setDefaultRenderer(Object.class, cell);

        JTableHeader h = table.getTableHeader();
        h.setOpaque(true);
        h.setBackground(NAVY_RAISED);
        h.setForeground(ACCENT_BRIGHT);
        h.setBorder(BorderFactory.createMatteBorder(0, 0, 2, 0, BORDER_BLUE));
        h.setFont(h.getFont().deriveFont(Font.BOLD));
    }

    public static void styleList(JList<?> list) {
        list.setOpaque(true);
        list.setBackground(BLACK_BLUE);
        list.setForeground(TEXT_BRIGHT);
        list.setSelectionBackground(ACCENT_BLUE);
        list.setSelectionForeground(Color.WHITE);
        list.setBorder(BorderFactory.createLineBorder(BORDER_BLUE));
    }

    public static void stylePanelFilled(JPanel p, Color bg) {
        p.setOpaque(true);
        p.setBackground(bg);
        p.setBorder(BorderFactory.createLineBorder(BORDER_BLUE));
    }

    public static TitledBorder framedSection(String titre) {
        Font base = UIManager.getFont("Label.font");
        if (base == null) {
            base = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        }
        return BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(BORDER_BLUE),
                titre,
                TitledBorder.LEFT,
                TitledBorder.TOP,
                base.deriveFont(Font.BOLD, 13f),
                ACCENT_BRIGHT);
    }

    /** Style pour petits boutons intégrés aux cellules du tableau (Défier / Voir). */
    public static void styleEmbeddedTableButton(AbstractButton b) {
        b.setOpaque(true);
        b.setFocusPainted(false);
        b.setBackground(NAVY_RAISED);
        b.setForeground(ACCENT_BRIGHT);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_BLUE),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
    }
}
