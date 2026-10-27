public class Plateau {
    private String[][] grille = new String[3][3];

    public Plateau() {
        reset();
    }

    public boolean jouerCoup(int ligne, int col, String symbole) {
        if (grille[ligne][col].equals("")) {
            grille[ligne][col] = symbole;
            return true;
        }
        return false;
    }

    public boolean estPlein() {
        for (String[] ligne : grille)
            for (String cell : ligne)
                if (cell.equals("")) return false;
        return true;
    }

    public String getGagnant() {
        LigneGagnante ligne = getGagnantEtLigneGagnante();
        return ligne == null ? null : ligne.symbole;
    }

    /** Returns null si pas de ligne gagnante. */
    public LigneGagnante getGagnantEtLigneGagnante() {
        for (int i = 0; i < 3; i++) {
            if (!grille[i][0].equals("") && grille[i][0].equals(grille[i][1]) && grille[i][1].equals(grille[i][2]))
                return new LigneGagnante(grille[i][0], new int[][]{{i, 0}, {i, 1}, {i, 2}});
            if (!grille[0][i].equals("") && grille[0][i].equals(grille[1][i]) && grille[1][i].equals(grille[2][i]))
                return new LigneGagnante(grille[0][i], new int[][]{{0, i}, {1, i}, {2, i}});
        }
        if (!grille[0][0].equals("") && grille[0][0].equals(grille[1][1]) && grille[1][1].equals(grille[2][2]))
            return new LigneGagnante(grille[0][0], new int[][]{{0, 0}, {1, 1}, {2, 2}});
        if (!grille[0][2].equals("") && grille[0][2].equals(grille[1][1]) && grille[1][1].equals(grille[2][0]))
            return new LigneGagnante(grille[0][2], new int[][]{{0, 2}, {1, 1}, {2, 0}});
        return null;
    }

    public int nombreCoups() {
        int n = 0;
        for (String[] ligne : grille) {
            for (String cell : ligne) {
                if (!cell.equals("")) {
                    n++;
                }
            }
        }
        return n;
    }

    public static final class LigneGagnante {
        public final String symbole;
        public final int[][] cellules;

        LigneGagnante(String symbole, int[][] cellules) {
            this.symbole = symbole;
            this.cellules = cellules;
        }
    }

    public void reset() {
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                grille[i][j] = "";
    }

    public String[][] getGrille() {
        return grille;
    }

    public String[][] copieGrille() {
        String[][] c = new String[3][3];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(grille[i], 0, c[i], 0, 3);
        }
        return c;
    }
}