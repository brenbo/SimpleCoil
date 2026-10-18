import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Tiny WAV tool for the game sounds.
 *   info <wav>...                      format, length, peak, where the sound starts and ends
 *   fx <in> <out> <maxSec> [hpHz] [fadeMs]  mono 44.1 kHz, trimmed, high-passed, cut to maxSec with a fade, peak -1 dBFS
 *   crop <in> <out> <start> <end>     just that part of the file, mono
 *   voice <in> <out>                   mono 22.05 kHz radio: band-passed 300-3400 Hz, a little drive, squelch clicks
 */
public class Snd {
    static float[] data; static int rate;

    static void read(String path) throws IOException {
        byte[] b = Files.readAllBytes(Paths.get(path));
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        if (b.length < 12 || bb.getInt(0) != 0x46464952 || bb.getInt(8) != 0x45564157) throw new IOException("not RIFF/WAVE: " + path);
        int pos = 12, fmt = 0, ch = 0, bits = 0; int dataPos = -1, dataLen = 0;
        while (pos + 8 <= b.length) {
            int id = bb.getInt(pos), len = bb.getInt(pos + 4);
            if (id == 0x20746d66) { fmt = bb.getShort(pos + 8) & 0xffff; ch = bb.getShort(pos + 10); rate = bb.getInt(pos + 12); bits = bb.getShort(pos + 22);
                if (fmt == 0xFFFE) fmt = bb.getShort(pos + 32) & 0xffff; }
            else if (id == 0x61746164) { dataPos = pos + 8; dataLen = Math.min(len, b.length - dataPos); break; }
            pos += 8 + len + (len & 1);
        }
        if (dataPos < 0) throw new IOException("no data: " + path);
        int bps = bits / 8, frames = dataLen / (bps * ch);
        data = new float[frames];
        for (int i = 0; i < frames; i++) {
            float sum = 0;
            for (int c = 0; c < ch; c++) {
                int o = dataPos + (i * ch + c) * bps; float v;
                if (fmt == 3 && bits == 32) v = bb.getFloat(o);
                else if (bits == 16) v = bb.getShort(o) / 32768f;
                else if (bits == 24) v = ((b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | b[o + 2] << 16) / 8388608f;
                else if (bits == 32) v = bb.getInt(o) / 2147483648f;
                else if (bits == 8) v = ((b[o] & 0xff) - 128) / 128f;
                else throw new IOException("bits " + bits);
                sum += v;
            }
            data[i] = sum / ch;
        }
        info = String.format("fmt=%d ch=%d rate=%d bits=%d", fmt, ch, rate, bits);
    }
    static String info;

    static void write(String path, float[] x, int r) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(44 + x.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0x46464952).putInt(36 + x.length * 2).putInt(0x45564157);
        bb.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1).putInt(r).putInt(r * 2).putShort((short) 2).putShort((short) 16);
        bb.putInt(0x61746164).putInt(x.length * 2);
        Random rnd = new Random(1);
        for (float v : x) { float d = (rnd.nextFloat() - rnd.nextFloat()) / 32768f; bb.putShort((short) Math.max(-32768, Math.min(32767, Math.round((v + d) * 32767)))); }
        Files.write(Paths.get(path), bb.array());
    }

    static float peak(float[] x) { float p = 0; for (float v : x) p = Math.max(p, Math.abs(v)); return p; }

    /** First and last sample above -40 dB of the peak, in a 5 ms window. */
    static int[] bounds(float[] x, int r) {
        float th = peak(x) * 0.01f; int w = Math.max(1, r / 200), s = 0, e = x.length;
        outer: for (int i = 0; i < x.length; i += w) for (int j = i; j < Math.min(x.length, i + w); j++) if (Math.abs(x[j]) > th) { s = Math.max(0, i - w); break outer; }
        for (int i = x.length - 1; i > s; i--) if (Math.abs(x[i]) > th) { e = Math.min(x.length, i + w * 4); break; }
        return new int[]{s, e};
    }

    static float[] resample(float[] x, int from, int to) {
        if (from == to) return x;
        // Low-pass first when going down (windowed sinc), then linear interpolation
        if (to < from) x = lowpass(x, from, to * 0.45f);
        int n = (int) ((long) x.length * to / from); float[] y = new float[n]; double step = (double) from / to;
        for (int i = 0; i < n; i++) { double p = i * step; int k = (int) p; double f = p - k; float a = x[Math.min(k, x.length - 1)], b = x[Math.min(k + 1, x.length - 1)]; y[i] = (float) (a + (b - a) * f); }
        return y;
    }

    static float[] lowpass(float[] x, int r, float hz) {
        int taps = 63; float[] h = new float[taps]; double fc = hz / r; double sum = 0;
        for (int i = 0; i < taps; i++) { int m = i - taps / 2; double s = m == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * m) / (Math.PI * m); double win = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (taps - 1)); h[i] = (float) (s * win); sum += h[i]; }
        for (int i = 0; i < taps; i++) h[i] /= sum;
        float[] y = new float[x.length];
        for (int i = 0; i < x.length; i++) { float acc = 0; for (int t = 0; t < taps; t++) { int k = i + t - taps / 2; if (k >= 0 && k < x.length) acc += x[k] * h[t]; } y[i] = acc; }
        return y;
    }

    /** RBJ biquad; type 0 high-pass, 1 low-pass. */
    static float[] biquad(float[] x, int r, double f0, int type, double q) {
        double w = 2 * Math.PI * f0 / r, cs = Math.cos(w), al = Math.sin(w) / (2 * q);
        double b0, b1, b2, a0 = 1 + al, a1 = -2 * cs, a2 = 1 - al;
        if (type == 0) { b0 = (1 + cs) / 2; b1 = -(1 + cs); b2 = (1 + cs) / 2; } else { b0 = (1 - cs) / 2; b1 = 1 - cs; b2 = (1 - cs) / 2; }
        float[] y = new float[x.length]; double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) { double v = (b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0; x2 = x1; x1 = x[i]; y2 = y1; y1 = v; y[i] = (float) v; }
        return y;
    }

    static float[] normalize(float[] x, float db) { float p = peak(x); if (p == 0) return x; float g = (float) Math.pow(10, db / 20) / p; for (int i = 0; i < x.length; i++) x[i] *= g; return x; }

    static float[] fades(float[] x, int r, float inMs, float outMs) {
        int fi = (int) (r * inMs / 1000), fo = (int) (r * outMs / 1000);
        for (int i = 0; i < Math.min(fi, x.length); i++) x[i] *= (float) i / fi;
        for (int i = 0; i < Math.min(fo, x.length); i++) x[x.length - 1 - i] *= (float) i / fo;
        return x;
    }

    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "info":
                for (int i = 1; i < a.length; i++) { read(a[i]); int[] bd = bounds(data, rate);
                    System.out.printf("%-40s %s len=%.2fs peak=%.2f sound=%.2f-%.2fs%n", Paths.get(a[i]).getFileName(), info, data.length / (double) rate, peak(data), bd[0] / (double) rate, bd[1] / (double) rate); }
                break;
            case "fx": {
                read(a[1]); float max = Float.parseFloat(a[3]); double hp = a.length > 4 ? Double.parseDouble(a[4]) : 90; float fadeMs = a.length > 5 ? Float.parseFloat(a[5]) : -1;
                int[] bd = bounds(data, rate); float[] x = Arrays.copyOfRange(data, bd[0], Math.min(bd[1], bd[0] + (int) (max * rate)));
                x = resample(x, rate, 44100);
                x = biquad(biquad(x, 44100, hp, 0, 0.707), 44100, hp, 0, 0.707); // phone speakers: no deep bass to waste power on
                boolean cut = bd[1] - bd[0] > max * rate;
                x = fades(x, 44100, 2, fadeMs >= 0 ? fadeMs : cut ? Math.min(150, max * 300) : 20);
                write(a[2], normalize(x, -1f), 44100);
                System.out.printf("%s -> %.2fs%n", Paths.get(a[2]).getFileName(), x.length / 44100.0);
                break;
            }
            case "crop": {
                // crop <in> <out> <startSec> <endSec>: just that part, mono, before fx
                read(a[1]);
                int s = (int) (Float.parseFloat(a[3]) * rate), e = Math.min(data.length, (int) (Float.parseFloat(a[4]) * rate));
                write(a[2], Arrays.copyOfRange(data, s, e), rate);
                break;
            }
            case "voice": {
                read(a[1]); int[] bd = bounds(data, rate); float[] x = Arrays.copyOfRange(data, bd[0], bd[1]);
                x = resample(x, rate, 22050); int r = 22050;
                x = biquad(biquad(x, r, 300, 0, 0.707), r, 300, 0, 0.707);
                x = biquad(biquad(x, r, 3400, 1, 0.707), r, 3400, 1, 0.707);
                x = normalize(x, -3f);
                for (int i = 0; i < x.length; i++) x[i] = (float) (Math.tanh(x[i] * 2.2) / Math.tanh(2.2)); // a little radio drive
                x = fades(x, r, 3, 15);
                // Squelch: a short click and hiss tail at each end, with a bed of quiet static under the line
                Random rnd = new Random(7); int pre = (int) (r * 0.06), post = (int) (r * 0.09);
                float[] y = new float[pre + x.length + post];
                for (int i = 0; i < y.length; i++) y[i] = (rnd.nextFloat() * 2 - 1) * 0.012f;
                for (int i = 0; i < x.length; i++) y[pre + i] += x[i] * 0.9f;
                int click = r / 400;
                for (int i = 0; i < click; i++) { float env = 1 - (float) i / click; y[i + 4] += (rnd.nextFloat() * 2 - 1) * 0.35f * env; }
                int tail = (int) (r * 0.07);
                for (int i = 0; i < tail; i++) { float env = (float) Math.exp(-i / (r * 0.02)); y[pre + x.length + i] += (rnd.nextFloat() * 2 - 1) * 0.18f * env; }
                y = biquad(y, r, 250, 0, 0.707); y = biquad(y, r, 3600, 1, 0.707);
                y = fades(y, r, 1, 8);
                write(a[2], normalize(y, -1.5f), r);
                System.out.printf("%s -> %.2fs%n", Paths.get(a[2]).getFileName(), y.length / (double) r);
                break;
            }
        }
    }
}
