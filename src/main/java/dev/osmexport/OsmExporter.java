package dev.osmexport;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.IntPredicate;
import net.fabricmc.loader.api.FabricLoader;

/** Builds the .osm XML. Cell (x,z) covers [x,x+1]x[z,z+1] meters; 1 block = 1 m. */
public final class OsmExporter {
    private OsmExporter() {}

    static int X(long k) { return (int) (k >> 32); }
    static int Z(long k) { return (int) k; }
    static long P(int x, int z) { return OsmData.key(x, z); }

    record Chain(List<Long> pts, boolean closed) {}

    // ---- boundary extraction: inside is always on the LEFT in map view (east=+x, north=-z)
    static Map<Long, ArrayDeque<Long>> edges(IntPredicate inside) {
        Map<Long, ArrayDeque<Long>> m = new HashMap<>();
        for (Map.Entry<Long, byte[]> e : OsmData.CELLS.entrySet()) {
            int cx = X(e.getKey()), cz = Z(e.getKey());
            byte[] d = e.getValue();
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    if (!inside.test(d[lz * 16 + lx])) continue;
                    int x = (cx << 4) + lx, z = (cz << 4) + lz;
                    if (out(inside, x, z - 1)) add(m, P(x + 1, z), P(x, z));
                    if (out(inside, x, z + 1)) add(m, P(x, z + 1), P(x + 1, z + 1));
                    if (out(inside, x + 1, z)) add(m, P(x + 1, z + 1), P(x + 1, z));
                    if (out(inside, x - 1, z)) add(m, P(x, z), P(x, z + 1));
                }
            }
        }
        return m;
    }

    static boolean out(IntPredicate in, int x, int z) {
        int v = OsmData.cell(x, z);
        return v >= 0 && !in.test(v); // unknown neighbors make no edge (chains stay open)
    }

    static void add(Map<Long, ArrayDeque<Long>> m, long a, long b) {
        m.computeIfAbsent(a, k -> new ArrayDeque<>()).add(b);
    }

    static List<Chain> chains(Map<Long, ArrayDeque<Long>> m) {
        Set<Long> hasIn = new HashSet<>();
        for (ArrayDeque<Long> q : m.values()) hasIn.addAll(q);
        List<Chain> res = new ArrayList<>();
        for (long s : new ArrayList<>(m.keySet()))      // open chains first
            if (!hasIn.contains(s)) while (m.containsKey(s)) res.add(walk(m, s));
        for (long s : new ArrayList<>(m.keySet()))      // then loops
            while (m.containsKey(s)) res.add(walk(m, s));
        return res;
    }

    static Chain walk(Map<Long, ArrayDeque<Long>> m, long s) {
        List<Long> pts = new ArrayList<>();
        pts.add(s);
        long cur = s;
        while (true) {
            ArrayDeque<Long> q = m.get(cur);
            if (q == null) return new Chain(pts, false);
            long nxt = q.poll();
            if (q.isEmpty()) m.remove(cur);
            pts.add(nxt);
            cur = nxt;
            if (cur == s) return new Chain(pts, true);
        }
    }

    static List<Long> simplify(List<Long> pts, boolean closed) {
        int n = pts.size();
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long b = pts.get(i);
            if (!closed && (i == 0 || i == n - 1)) { out.add(b); continue; }
            long a = pts.get((i - 1 + n) % n), c = pts.get((i + 1) % n);
            long dx1 = X(b) - X(a), dz1 = Z(b) - Z(a), dx2 = X(c) - X(b), dz2 = Z(c) - Z(b);
            if (dx1 * dz2 - dz1 * dx2 == 0 && dx1 * dx2 + dz1 * dz2 > 0) continue;
            out.add(b);
        }
        return out;
    }

    static long area2(List<Long> r) { // >0 = outer (CCW in map view)
        long s = 0;
        for (int i = 0; i < r.size(); i++) {
            long a = r.get(i), b = r.get((i + 1) % r.size());
            s += (long) X(a) * -Z(b) - (long) X(b) * -Z(a);
        }
        return s;
    }

    static boolean inside(List<Long> r, double px, double pz) {
        boolean in = false;
        for (int i = 0, j = r.size() - 1; i < r.size(); j = i++) {
            double xi = X(r.get(i)), zi = Z(r.get(i)), xj = X(r.get(j)), zj = Z(r.get(j));
            if ((zi > pz) != (zj > pz) && px < (xj - xi) * (pz - zi) / (zj - zi) + xi) in = !in;
        }
        return in;
    }

    // ---- XML writer
    static final class Xml {
        final StringBuilder nodes = new StringBuilder(), ways = new StringBuilder(), rels = new StringBuilder();
        final Map<Long, Long> grid = new HashMap<>();
        long next = -1;
        final double lat0 = OsmData.originLat, lon0 = OsmData.originLon;
        final double mLat = 111320.0, mLon = 111320.0 * Math.cos(Math.toRadians(OsmData.originLat));

        long node(double x, double z) {
            long id = next--;
            nodes.append(String.format(Locale.ROOT,
                "  <node id=\"%d\" visible=\"true\" version=\"1\" lat=\"%.7f\" lon=\"%.7f\"/>\n",
                id, lat0 - z / mLat, lon0 + x / mLon));
            return id;
        }

        long gridNode(long k) { return grid.computeIfAbsent(k, kk -> node(X(kk), Z(kk))); }

        long way(List<Long> refs, String... kv) {
            long id = next--;
            ways.append("  <way id=\"").append(id).append("\" visible=\"true\" version=\"1\">\n");
            for (long r : refs) ways.append("    <nd ref=\"").append(r).append("\"/>\n");
            tags(ways, "    ", kv);
            ways.append("  </way>\n");
            return id;
        }

        long gridWay(List<Long> pts, boolean closed, String... kv) {
            List<Long> refs = new ArrayList<>();
            for (long k : pts) refs.add(gridNode(k));
            if (closed) refs.add(refs.get(0));
            return way(refs, kv);
        }

        static void tags(StringBuilder sb, String ind, String... kv) {
            for (int i = 0; i + 1 < kv.length; i += 2)
                sb.append(ind).append("<tag k=\"").append(esc(kv[i])).append("\" v=\"").append(esc(kv[i + 1])).append("\"/>\n");
        }

        static String esc(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
        }
    }

    public static Path export(String fileName) throws IOException {
        Xml x = new Xml();

        // Coastlines: land on the left, ocean on the right (OSM convention). River cells count as land,
        // so the coastline closes across river mouths.
        for (Chain c : chains(edges(v -> (v & 1) == 0))) {
            List<Long> p = new ArrayList<>(c.pts());
            if (c.closed()) p.remove(p.size() - 1);
            p = simplify(p, c.closed());
            if (p.size() < (c.closed() ? 3 : 2)) continue;
            x.gridWay(p, c.closed(), "natural", "coastline");
        }

        // Rivers: river-biome water as areas (outer rings + holes as a multipolygon)
        List<List<Long>> outers = new ArrayList<>(), inners = new ArrayList<>();
        for (Chain c : chains(edges(v -> (v & 2) != 0))) {
            List<Long> p = new ArrayList<>(c.pts());
            if (c.closed()) p.remove(p.size() - 1);      // open chains get closed by a straight line
            p = simplify(p, true);
            if (p.size() < 3) continue;
            (area2(p) > 0 ? outers : inners).add(p);
        }
        Map<List<Long>, List<List<Long>>> holes = new HashMap<>();
        for (List<Long> in : inners) {
            double mx = (X(in.get(0)) + X(in.get(1))) / 2.0, mz = (Z(in.get(0)) + Z(in.get(1))) / 2.0;
            List<Long> best = null;
            for (List<Long> o : outers)
                if (inside(o, mx, mz) && (best == null || Math.abs(area2(o)) < Math.abs(area2(best)))) best = o;
            if (best != null) holes.computeIfAbsent(best, k -> new ArrayList<>()).add(in);
        }
        for (List<Long> o : outers) {
            List<List<Long>> hs = holes.getOrDefault(o, List.of());
            if (hs.isEmpty()) {
                x.gridWay(o, true, "natural", "water", "water", "river");
            } else {
                long oid = x.gridWay(o, true);
                long rid = x.next--;
                x.rels.append("  <relation id=\"").append(rid).append("\" visible=\"true\" version=\"1\">\n");
                x.rels.append("    <member type=\"way\" ref=\"").append(oid).append("\" role=\"outer\"/>\n");
                for (List<Long> h : hs)
                    x.rels.append("    <member type=\"way\" ref=\"").append(x.gridWay(h, true)).append("\" role=\"inner\"/>\n");
                Xml.tags(x.rels, "    ", "type", "multipolygon", "natural", "water", "water", "river");
                x.rels.append("  </relation>\n");
            }
        }

        // Player-drawn lines: only a name, no feature tags
        for (OsmData.Line l : OsmData.LINES) {
            if (l.pts.size() < 2) continue;
            List<Long> refs = new ArrayList<>();
            for (double[] pt : l.pts) refs.add(x.node(pt[0], pt[1]));
            x.way(refs, "name", l.name);
        }

        Path dir = FabricLoader.getInstance().getGameDir().resolve("osm_exports");
        Files.createDirectories(dir);
        Path file = dir.resolve(fileName + ".osm");
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<osm version=\"0.6\" generator=\"osmexport\">\n"
            + x.nodes + x.ways + x.rels + "</osm>\n";
        Files.writeString(file, xml);
        return file;
    }
}
