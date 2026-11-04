package edu.umkc.advisor.retrieval;

public final class Vectors {
    private Vectors() {}

    public static float[] normalize(float[] v) {
        double n = 0;
        for (float x : v) n += (double) x * x;
        n = Math.sqrt(n);
        if (n == 0) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / n);
        return out;
    }

    /** Cosine similarity for already-normalized vectors. */
    public static double dot(float[] a, float[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("dimension mismatch");
        double s = 0;
        for (int i = 0; i < a.length; i++) s += (double) a[i] * b[i];
        return s;
    }

    public static String toPgLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(','); sb.append(v[i]); }
        return sb.append(']').toString();
    }
}
