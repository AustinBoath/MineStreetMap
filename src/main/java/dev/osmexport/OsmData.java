package dev.osmexport;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/** All mod state (in memory) + world scanning. */
public final class OsmData {
    private OsmData() {}

    /** Per scanned chunk: 256 cells. Bit0 = ocean water, bit1 = river water. Missing chunk = unknown. */
    public static final Map<Long, byte[]> CELLS = new HashMap<>();
    public static final List<Line> LINES = new ArrayList<>();
    public static final Map<UUID, Line> ACTIVE = new HashMap<>();
    public static double originLat = 0, originLon = 0;

    public static final class Line {
        public final String name;
        public final List<double[]> pts = new ArrayList<>();
        public Line(String name) { this.name = name; }
    }

    public static long key(int a, int b) { return ((long) a << 32) | (b & 0xFFFFFFFFL); }

    /** -1 = unknown, otherwise flags. x,z are block coordinates. */
    public static int cell(int x, int z) {
        byte[] d = CELLS.get(key(x >> 4, z >> 4));
        return d == null ? -1 : d[(z & 15) * 16 + (x & 15)];
    }

    private static boolean water(BlockState s) {
        return s.getFluidState().is(FluidTags.WATER) || s.is(BlockTags.ICE);
    }

    /** Scans already-loaded chunks within radius (chunks) of the given chunk. Returns chunks scanned. */
    public static int scan(ServerLevel level, int pcx, int pcz, int radius) {
        int n = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = pcx + dx, cz = pcz + dz;
                LevelChunk ch = level.getChunkSource().getChunkNow(cx, cz);
                if (ch == null) continue;
                byte[] d = new byte[256];
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int wx = (cx << 4) + lx, wz = (cz << 4) + lz;
                        int y = ch.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz) - 1;
                        BlockPos pos = new BlockPos(wx, y, wz);
                        // current blocks, so player edits count (top or one below handles lily pads, snow on ice)
                        if (water(ch.getBlockState(pos)) || water(ch.getBlockState(pos.below()))) {
                            Holder<Biome> b = level.getBiome(pos);
                            if (b.is(BiomeTags.IS_OCEAN)) d[lz * 16 + lx] = 1;
                            else if (b.is(BiomeTags.IS_RIVER)) d[lz * 16 + lx] = 2;
                        }
                    }
                }
                CELLS.put(key(cx, cz), d);
                n++;
            }
        }
        return n;
    }

    public static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        for (Map.Entry<UUID, Line> e : ACTIVE.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) continue;
            List<double[]> pts = e.getValue().pts;
            double x = p.getX(), z = p.getZ();
            if (pts.isEmpty()) { pts.add(new double[] {x, z}); continue; }
            double[] last = pts.get(pts.size() - 1);
            if (Math.hypot(x - last[0], z - last[1]) >= 1.0) pts.add(new double[] {x, z});
        }
    }
}
