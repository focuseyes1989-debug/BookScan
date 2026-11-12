package app.bookscan;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class TableStraightener {
    private static final int COLUMNS = 32;
    private static final int MESH_ROWS = 192;

    static Bitmap straighten(Bitmap source) {
        float scale = Math.min(1f, 760f / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.round(source.getWidth() * scale);
        int height = Math.round(source.getHeight() * scale);
        if (width < 160 || height < 120) return null;
        Bitmap small = Bitmap.createScaledBitmap(source, width, height, true);
        int[] pixels = new int[width * height];
        small.getPixels(pixels, 0, width, 0, 0, width, height);
        if (small != source) small.recycle();
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            pixels[i] = (54 * Color.red(pixel) + 183 * Color.green(pixel) + 19 * Color.blue(pixel)) >> 8;
        }

        List<int[]> lines = detect(pixels, width, height);
        if (lines.size() < 4) return null;
        float[] mesh = mesh(lines, source.getWidth(), source.getHeight(), height);
        Bitmap output = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmapMesh(source, COLUMNS, MESH_ROWS, mesh, 0, null, 0,
                new Paint(Paint.FILTER_BITMAP_FLAG));
        return output;
    }

    static List<int[]> detect(int[] gray, int width, int height) {
        float[][] scores = lineScores(gray, width, height);
        int center = COLUMNS / 2;
        List<int[]> lines = new ArrayList<>();
        int minSeparation = Math.max(5, height / 105);
        for (int y = height / 30; y < height * 29 / 30; y++) {
            float[] middle = scores[center];
            if (middle[y] < 8 || peak(middle, y, 2) != y) continue;
            int[] line = new int[COLUMNS + 1];
            line[center] = y;
            int hits = 1 + follow(scores, line, center, -1) + follow(scores, line, center, 1);
            if (hits < 23 || !spansTable(line)) continue;
            if (!lines.isEmpty()) {
                int[] prior = lines.get(lines.size() - 1);
                boolean overlaps = false;
                for (int x = 0; x <= COLUMNS; x++) {
                    if (line[x] - prior[x] < minSeparation) {
                        overlaps = true;
                        break;
                    }
                }
                if (overlaps) continue;
            }
            lines.add(line);
        }
        return lines;
    }

    private static float[][] lineScores(int[] gray, int width, int height) {
        float[][] scores = new float[COLUMNS + 1][height];
        int radius = Math.max(4, width / COLUMNS / 2);
        for (int column = 1; column < COLUMNS; column++) {
            int center = width * column / COLUMNS;
            int left = Math.max(3, center - radius);
            int right = Math.min(width - 4, center + radius);
            for (int y = 3; y < height - 3; y++) {
                int sum = 0;
                int count = 0;
                for (int x = left; x <= right; x++) {
                    int p = y * width + x;
                    int ink = gray[p];
                    int surroundings = (gray[p - 3 * width] + gray[p + 3 * width]) / 2;
                    int contrast = surroundings - ink;
                    if (contrast >= 13 && gray[p - 2] < surroundings - 8 && gray[p + 2] < surroundings - 8) {
                        sum += Math.min(100, contrast);
                        count++;
                    }
                }
                if (count * 3 >= right - left + 1) scores[column][y] = sum / (float) (right - left + 1);
            }
        }
        return scores;
    }

    private static int peak(float[] scores, int expected, int radius) {
        int result = expected;
        for (int y = Math.max(3, expected - radius); y <= Math.min(scores.length - 4, expected + radius); y++) {
            if (scores[y] > scores[result]) result = y;
        }
        return result;
    }

    private static int follow(float[][] scores, int[] line, int center, int direction) {
        int hits = 0;
        int last = line[center];
        int drift = 0;
        int missing = 0;
        for (int x = center + direction; x > 0 && x < COLUMNS; x += direction) {
            float[] score = scores[x];
            int expected = Math.max(3, Math.min(score.length - 4, last + drift));
            int found = expected;
            float best = 4;
            for (int y = Math.max(3, expected - 6); y <= Math.min(score.length - 4, expected + 6); y++) {
                float rank = score[y] - 1.8f * Math.abs(y - expected);
                if (score[y] >= 7 && rank > best && peak(score, y, 1) == y) {
                    found = y;
                    best = rank;
                }
            }
            if (best > 4) {
                hits++;
                drift = missing > 0 ? 0 : Math.max(-3, Math.min(3, (found - last + drift) / 2));
                missing = 0;
            } else if (++missing > 1) {
                drift = 0;
                found = last;
            }
            line[x] = found;
            last = found;
        }
        line[direction < 0 ? 0 : COLUMNS] = last;
        return hits;
    }

    private static boolean spansTable(int[] line) {
        int min = line[0];
        int max = line[0];
        for (int y : line) {
            min = Math.min(min, y);
            max = Math.max(max, y);
        }
        return max - min <= 60;
    }

    private static float[] mesh(List<int[]> lines, int width, int height, int analysisHeight) {
        float[] vertices = new float[(COLUMNS + 1) * (MESH_ROWS + 1) * 2];
        float[] targets = new float[lines.size() + 2];
        targets[targets.length - 1] = analysisHeight;
        for (int i = 0; i < lines.size(); i++) {
            int[] sorted = lines.get(i).clone();
            Arrays.sort(sorted);
            targets[i + 1] = sorted[COLUMNS / 2];
        }
        for (int x = 0; x <= COLUMNS; x++) {
            float[] anchors = new float[targets.length];
            anchors[anchors.length - 1] = analysisHeight;
            for (int i = 0; i < lines.size(); i++) {
                int[] line = lines.get(i);
                int left = Math.max(0, x - 1);
                int right = Math.min(COLUMNS, x + 1);
                anchors[i + 1] = (line[left] + 2f * line[x] + line[right]) / 4f;
            }
            int band = 0;
            for (int row = 0; row <= MESH_ROWS; row++) {
                float sourceY = analysisHeight * row / (float) MESH_ROWS;
                while (band + 2 < anchors.length && sourceY > anchors[band + 1]) band++;
                float progress = (sourceY - anchors[band]) / (anchors[band + 1] - anchors[band]);
                float mappedY = targets[band] + progress * (targets[band + 1] - targets[band]);
                int offset = 2 * (row * (COLUMNS + 1) + x);
                vertices[offset] = width * x / (float) COLUMNS;
                vertices[offset + 1] = mappedY * height / analysisHeight;
            }
        }
        return vertices;
    }

    private TableStraightener() {}
}
