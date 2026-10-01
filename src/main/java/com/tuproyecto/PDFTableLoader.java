package com.tuproyecto;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PDFTableLoader — Lee PDFs de tablas de Bingo (transcritas o generadas como
 * texto), incluso con varias tablas por página (lado a lado o apiladas),
 * códigos con guion o con "#" delante (ej. "#0218873", "83081-3"), y tablas
 * ESPECIALES con casillas en blanco (representadas con "-", vacío, un logo
 * (imagen, sin texto), o cualquier palabra no numérica).
 *
 * Funciona por POSICIÓN (x, y) de cada palabra en la página, no por el
 * orden del texto (que se desordena cuando hay varias tablas por página):
 *
 *   1. Ubica los "códigos" de tabla: opcionalmente un "#", dígitos (4 a 8)
 *      opcionalmente seguidos de un guion y más dígitos.
 *   2. Ubica el encabezado "B I N G O" y todo lo demás cerca de cada código.
 *   3. Arma la grilla 5x5 por CERCANÍA (clustering), usando la posición X de
 *      las letras B-I-N-G-O de cada tabla como referencia de columna (así no
 *      importa si falta la casilla central por ser un logo/imagen sin texto,
 *      o si algunas celdas no se transcribieron exactamente iguales).
 *   4. Si la tabla tiene exactamente 1 casilla vacía → se importa como
 *      tabla NORMAL (esa es la casilla libre de siempre). Si tiene más de
 *      1 → se importa como tabla ESPECIAL (deja las demás en blanco).
 */
public class PDFTableLoader {

    /** Código de tabla: "#" opcional, dígitos (4-8), con guion y más dígitos opcional. */
    private static final Pattern CODE_TOKEN = Pattern.compile("^#?(\\d{4,8}(-\\d{1,4})?)$");

    /** Un número válido de casilla de bingo (1 o 2 dígitos). */
    private static final Pattern NUMBER_TOKEN = Pattern.compile("^\\d{1,2}$");

    /** Letras de encabezado: se usan como referencia de columna, no como dato de celda. */
    private static final Set<String> HEADER_LETTERS = Set.of("B", "I", "N", "G", "O");

    /** Alto por defecto de la región de una tabla si no hay otra tabla debajo (en puntos PDF). */
    private static final float DEFAULT_ROW_HEIGHT = 260f;

    /**
     * Cantidad mínima de celdas numéricas/con texto detectadas para confiar
     * en la tabla. Se mantiene BAJA a propósito: las tablas ESPECIALES
     * (figuras) suelen tener muy pocas casillas llenas —a veces menos de
     * 10— y es perfectamente válido. Solo sirve para descartar ruido total
     * (una región vacía cerca de un código que en realidad no es una tabla).
     */
    private static final int MIN_CELL_WORDS = 3;

    // -----------------------------------------------------------------------
    //  MÉTODO PRINCIPAL
    // -----------------------------------------------------------------------
    public static List<BingoTableData> loadTablesFromPDF(File pdfFile) throws IOException {
        List<BingoTableData> result = new ArrayList<>();
        int codesFound = 0;
        try (PDDocument doc = PDDocument.load(pdfFile)) {
            for (int pageIndex = 0; pageIndex < doc.getNumberOfPages(); pageIndex++) {
                List<Word> words = extractWords(doc, pageIndex);
                PageParseResult pageResult = parsePageWords(words);
                result.addAll(pageResult.tables);
                codesFound += pageResult.codesFound;
            }
        }
        if (result.isEmpty()) {
            if (codesFound == 0) {
                throw new IOException(
                    "No se pudo leer ninguna tabla del PDF.\n\n" +
                    "Verifique que:\n" +
                    "  • El PDF tenga texto real (no sea una foto/escaneo de la tabla).\n" +
                    "  • Cada tabla tenga su código visible arriba de la grilla.\n" +
                    "  • La grilla tenga 5 columnas (B-I-N-G-O) y 5 filas."
                );
            } else {
                throw new IOException(
                    "Se detectaron " + codesFound + " código(s) de tabla en el PDF, pero no se " +
                    "pudo interpretar correctamente la grilla de números de ninguno.\n\n" +
                    "Esto suele pasar cuando:\n" +
                    "  • Los números de la grilla quedaron muy pegados o desalineados en el PDF.\n" +
                    "  • Falta demasiado texto (muchas celdas ilegibles) en la transcripción.\n" +
                    "Intente generar el PDF con mejor espaciado/alineación entre celdas."
                );
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    //  EXTRACCIÓN DE PALABRAS CON POSICIÓN (x, y)
    // -----------------------------------------------------------------------
    private static List<Word> extractWords(PDDocument doc, int pageIndex) throws IOException {
        List<Word> words = new ArrayList<>();

        PDFTextStripper stripper = new PDFTextStripper() {
            private final StringBuilder current = new StringBuilder();
            private float startX, startY, lastEndX;
            private boolean building = false;

            @Override
            protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
                for (TextPosition tp : textPositions) {
                    String unicode = tp.getUnicode();
                    char c = (unicode == null || unicode.isEmpty()) ? ' ' : unicode.charAt(0);

                    if (Character.isWhitespace(c)) {
                        flush();
                        continue;
                    }

                    float x = tp.getXDirAdj();
                    float y = tp.getYDirAdj();
                    float w = Math.max(tp.getWidthDirAdj(), 1f);

                    if (!building) {
                        building = true;
                        current.setLength(0);
                        startX = x;
                        startY = y;
                    } else {
                        float gap = x - lastEndX;
                        // Un espacio mayor a ~1.4 veces el ancho del carácter anterior
                        // se considera un salto de palabra (evita pegar celdas contiguas).
                        if (gap > w * 1.4f + 1.5f) {
                            flush();
                            building = true;
                            current.setLength(0);
                            startX = x;
                            startY = y;
                        }
                    }
                    current.append(c);
                    lastEndX = x + w;
                }
                flush();
            }

            private void flush() {
                if (building && current.length() > 0) {
                    words.add(new Word(current.toString(), startX, startY));
                }
                building = false;
                current.setLength(0);
            }
        };

        stripper.setSortByPosition(true);
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.getText(doc); // dispara writeString(...) por cada línea detectada
        return words;
    }

    // -----------------------------------------------------------------------
    //  PARSEO DE UNA PÁGINA (posiblemente con varias tablas)
    // -----------------------------------------------------------------------
    private static PageParseResult parsePageWords(List<Word> words) {
        List<BingoTableData> tables = new ArrayList<>();

        // 1. Códigos de tabla en esta página (acepta "#0218873", "83081-3", etc).
        List<Word> codeWords = new ArrayList<>();
        for (Word w : words) {
            if (CODE_TOKEN.matcher(w.text).matches()) codeWords.add(w);
        }
        if (codeWords.isEmpty()) return new PageParseResult(tables, 0);

        // 2. Candidatas a celda: todo lo que no sea un código ni una letra de
        //    encabezado. Incluye números, guiones, "FREE", "VERITO", etc.
        //    (cualquier cosa no numérica se tratará como casilla vacía).
        List<Word> cellCandidates = new ArrayList<>();
        // 2b. Letras de encabezado (B-I-N-G-O), se guardan aparte: sirven de
        //     referencia para saber la posición X exacta de cada columna.
        List<Word> headerCandidates = new ArrayList<>();
        for (Word w : words) {
            if (CODE_TOKEN.matcher(w.text).matches()) continue;
            if (HEADER_LETTERS.contains(w.text)) {
                headerCandidates.add(w);
                continue;
            }
            cellCandidates.add(w);
        }

        // 3. Agrupar los códigos en "filas de tablas" según su cercanía vertical
        //    (varias tablas lado a lado tienen su código casi a la misma altura;
        //    varias tablas apiladas quedan cada una en su propia fila).
        codeWords.sort(Comparator.comparing(w -> w.y));
        List<List<Word>> codeRows = new ArrayList<>();
        List<Word> currentCodeRow = new ArrayList<>();
        float lastCodeY = Float.NaN;
        for (Word w : codeWords) {
            if (currentCodeRow.isEmpty() || Math.abs(w.y - lastCodeY) <= 25f) {
                currentCodeRow.add(w);
            } else {
                codeRows.add(currentCodeRow);
                currentCodeRow = new ArrayList<>();
                currentCodeRow.add(w);
            }
            lastCodeY = w.y;
        }
        if (!currentCodeRow.isEmpty()) codeRows.add(currentCodeRow);

        float[] rowTopY = new float[codeRows.size()];
        float[] rowBottomLimit = new float[codeRows.size()];
        for (int i = 0; i < codeRows.size(); i++) {
            codeRows.get(i).sort(Comparator.comparing(w -> w.x));
            rowTopY[i] = codeRows.get(i).get(0).y;
            rowBottomLimit[i] = (i + 1 < codeRows.size())
                ? codeRows.get(i + 1).get(0).y - 5f
                : rowTopY[i] + DEFAULT_ROW_HEIGHT;
        }

        // 4. Para cada fila de tablas, asignar cada celda candidata (y cada
        //    letra de encabezado) a la tabla (código) más cercana en
        //    horizontal, dentro de la franja vertical de esa fila.
        for (int rowIdx = 0; rowIdx < codeRows.size(); rowIdx++) {
            List<Word> rowCodes = codeRows.get(rowIdx);
            float top = rowTopY[rowIdx] + 6f;   // estrictamente debajo de la línea del código
            float bottom = rowBottomLimit[rowIdx];
            float headerTop = rowTopY[rowIdx] - 2f; // el header puede quedar más pegado al código

            List<List<Word>> cellBuckets = new ArrayList<>();
            List<List<Word>> headerBuckets = new ArrayList<>();
            for (int k = 0; k < rowCodes.size(); k++) {
                cellBuckets.add(new ArrayList<>());
                headerBuckets.add(new ArrayList<>());
            }

            for (Word w : cellCandidates) {
                if (w.y < top || w.y > bottom) continue;
                cellBuckets.get(nearestByX(w, rowCodes)).add(w);
            }
            for (Word w : headerCandidates) {
                if (w.y < headerTop || w.y > bottom) continue;
                headerBuckets.get(nearestByX(w, rowCodes)).add(w);
            }

            for (int k = 0; k < rowCodes.size(); k++) {
                String rawCode = rowCodes.get(k).text;
                String code = rawCode.startsWith("#") ? rawCode.substring(1) : rawCode;
                BingoTableData table = buildTableFromCells(code, cellBuckets.get(k), headerBuckets.get(k));
                if (table != null) tables.add(table);
            }
        }

        return new PageParseResult(tables, codeWords.size());
    }

    private static int nearestByX(Word w, List<Word> candidates) {
        int nearest = 0;
        float bestDist = Float.MAX_VALUE;
        for (int k = 0; k < candidates.size(); k++) {
            float d = Math.abs(w.x - candidates.get(k).x);
            if (d < bestDist) { bestDist = d; nearest = k; }
        }
        return nearest;
    }

    /**
     * Arma una tabla 5x5 a partir de las palabras detectadas dentro de su
     * región, usando CLUSTERING en vez de exigir exactamente 25 palabras:
     *
     *   - Columnas: si se detectaron las 5 letras del encabezado (B-I-N-G-O)
     *     de esa tabla, se usa su posición X real como referencia de columna
     *     (lo más preciso). Si no, se agrupan las X de las celdas en 5
     *     grupos automáticamente.
     *   - Filas: se agrupan las Y de las celdas en 5 grupos automáticamente.
     *
     * Así, si la casilla central es un logo/imagen sin texto (o cualquier
     * otra celda no se transcribió), la tabla igual se arma correctamente:
     * esa celda queda simplemente vacía (0), en vez de invalidar toda la
     * tabla por no sumar exactamente 25 palabras.
     *
     * Cualquier celda cuyo texto no sea un número de 1-2 dígitos válido
     * (1-90) se considera vacía. Si la tabla resulta con más de 1 casilla
     * vacía, se marca como ESPECIAL; con exactamente 1, como NORMAL (la
     * casilla libre de siempre).
     */
    private static BingoTableData buildTableFromCells(String code, List<Word> cellWords, List<Word> headerWords) {
        if (cellWords.size() < MIN_CELL_WORDS) return null;

        // --- Referencias de columna (X) ---
        float[] colAnchors;
        if (headerWords.size() >= 4 && headerWords.size() <= 6) {
            List<Word> sortedHeader = new ArrayList<>(headerWords);
            sortedHeader.sort(Comparator.comparing(w -> w.x));
            if (sortedHeader.size() > 5) sortedHeader = sortedHeader.subList(0, 5);
            colAnchors = new float[sortedHeader.size()];
            for (int i = 0; i < sortedHeader.size(); i++) colAnchors[i] = sortedHeader.get(i).x;
            if (colAnchors.length < 5) colAnchors = kmeans1D(extractX(cellWords), 5);
        } else {
            colAnchors = kmeans1D(extractX(cellWords), 5);
        }

        // --- Referencias de fila (Y) ---
        float[] rowAnchors = kmeans1D(extractY(cellWords), 5);

        // --- Asignar cada palabra a la celda (fila, columna) más cercana ---
        int[][] numbers = new int[5][5];
        boolean[][] filled = new boolean[5][5];

        for (Word w : cellWords) {
            int col = nearestIndex(w.x, colAnchors);
            int row = nearestIndex(w.y, rowAnchors);
            if (filled[row][col]) continue; // ya hay un valor válido en esa celda, no lo pises
            String token = w.text;
            if (NUMBER_TOKEN.matcher(token).matches()) {
                int n = Integer.parseInt(token);
                // Validar contra el rango real de ESA columna (bingo de 75 bolas:
                // B 1-15, I 16-30, N 31-45, G 46-60, O 61-75), no solo "1 a 90" en
                // general — así se detecta si un número quedó mal ubicado.
                int[] range = BingoGUI.COLUMN_RANGES[col];
                if (n >= range[0] && n <= range[1]) {
                    numbers[row][col] = n;
                    filled[row][col] = true;
                }
            }
            // Cualquier otra cosa (guion, "FREE", "VERITO", logo transcrito
            // como texto, etc.) simplemente no llena la celda: queda vacía.
        }

        int blanks = 0;
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 5; c++) {
                if (!filled[r][c]) blanks++;
            }
        }

        boolean special = blanks > 1;
        return new BingoTableData(code, numbers, special);
    }

    private static List<Float> extractX(List<Word> ws) {
        List<Float> xs = new ArrayList<>(ws.size());
        for (Word w : ws) xs.add(w.x);
        return xs;
    }

    private static List<Float> extractY(List<Word> ws) {
        List<Float> ys = new ArrayList<>(ws.size());
        for (Word w : ws) ys.add(w.y);
        return ys;
    }

    private static int nearestIndex(float value, float[] anchors) {
        int nearest = 0;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < anchors.length; i++) {
            float d = Math.abs(value - anchors[i]);
            if (d < bestDist) { bestDist = d; nearest = i; }
        }
        return nearest;
    }

    /**
     * Agrupa una lista de valores 1D en k grupos (k-means simple) y devuelve
     * los k centroides, ORDENADOS de menor a mayor. Se usa para detectar
     * automáticamente las 5 filas o las 5 columnas de una tabla sin depender
     * de que haya exactamente una palabra por celda.
     */
    private static float[] kmeans1D(List<Float> values, int k) {
        if (values.isEmpty()) {
            float[] fallback = new float[k];
            for (int i = 0; i < k; i++) fallback[i] = i;
            return fallback;
        }
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : values) { if (v < min) min = v; if (v > max) max = v; }

        float[] centroids = new float[k];
        for (int i = 0; i < k; i++) {
            centroids[i] = (max > min) ? (min + (max - min) * i / (k - 1)) : min;
        }

        for (int iter = 0; iter < 20; iter++) {
            float[] sum = new float[k];
            int[] count = new int[k];
            for (float v : values) {
                int idx = nearestIndex(v, centroids);
                sum[idx] += v;
                count[idx]++;
            }
            boolean changed = false;
            for (int i = 0; i < k; i++) {
                if (count[i] > 0) {
                    float newCentroid = sum[i] / count[i];
                    if (Math.abs(newCentroid - centroids[i]) > 0.01f) changed = true;
                    centroids[i] = newCentroid;
                }
            }
            if (!changed) break;
        }

        java.util.Arrays.sort(centroids);
        return centroids;
    }

    // -----------------------------------------------------------------------
    //  CLASES AUXILIARES
    // -----------------------------------------------------------------------
    private static class Word {
        final String text;
        final float x;
        final float y;
        Word(String text, float x, float y) {
            this.text = text;
            this.x = x;
            this.y = y;
        }
    }

    private static class PageParseResult {
        final List<BingoTableData> tables;
        final int codesFound;
        PageParseResult(List<BingoTableData> tables, int codesFound) {
            this.tables = tables;
            this.codesFound = codesFound;
        }
    }

    public static class BingoTableData {
        private final String code;
        private final int[][] numbers;
        private final boolean special;

        public BingoTableData(String code, int[][] numbers, boolean special) {
            this.code    = code;
            this.numbers = numbers;
            this.special = special;
        }

        public String   getCode()    { return code; }
        public int[][]  getNumbers() { return numbers; }
        public boolean  isSpecial()  { return special; }
    }
}
