package dev.osmexport;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

public final class OsmCommands {
    private OsmCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("osm")
            .executes(c -> menu(c.getSource()))
            .then(Commands.literal("coast")
                .executes(c -> coast(c, 16))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                    .executes(c -> coast(c, IntegerArgumentType.getInteger(c, "radius")))))
            .then(Commands.literal("line")
                .then(Commands.literal("start")
                    .then(Commands.argument("name", StringArgumentType.greedyString()).executes(OsmCommands::lineStart)))
                .then(Commands.literal("end").executes(c -> lineEnd(c, true)))
                .then(Commands.literal("cancel").executes(c -> lineEnd(c, false))))
            .then(Commands.literal("origin")
                .then(Commands.argument("lat", DoubleArgumentType.doubleArg(-85, 85))
                    .then(Commands.argument("lon", DoubleArgumentType.doubleArg(-180, 180)).executes(c -> {
                        OsmData.originLat = DoubleArgumentType.getDouble(c, "lat");
                        OsmData.originLon = DoubleArgumentType.getDouble(c, "lon");
                        say(c, "Origin (x=0,z=0) set to " + OsmData.originLat + ", " + OsmData.originLon);
                        return 1;
                    }))))
            .then(Commands.literal("status").executes(c -> {
                say(c, OsmData.CELLS.size() + " chunks scanned, " + OsmData.LINES.size() + " lines, origin "
                    + OsmData.originLat + ", " + OsmData.originLon);
                return 1;
            }))
            .then(Commands.literal("clear").executes(c -> {
                OsmData.CELLS.clear(); OsmData.LINES.clear(); OsmData.ACTIVE.clear();
                say(c, "Cleared scan data and lines.");
                return 1;
            }))
            .then(Commands.literal("export")
                .executes(c -> export(c, "world"))
                .then(Commands.argument("file", StringArgumentType.word())
                    .executes(c -> export(c, StringArgumentType.getString(c, "file"))))));
    }

    // ---- helpers
    private static void say(CommandContext<CommandSourceStack> c, String s) {
        c.getSource().sendSuccess(() -> Component.literal(s), false);
    }

    private static MutableComponent run(String label, String cmd) {
        return Component.literal(label).withStyle(s -> s.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent.RunCommand(cmd)));
    }

    private static MutableComponent suggest(String label, String cmd) {
        return Component.literal(label).withStyle(s -> s.withColor(ChatFormatting.GREEN).withClickEvent(new ClickEvent.SuggestCommand(cmd)));
    }

    private static int menu(CommandSourceStack src) {
        MutableComponent m = Component.literal("OSM: ")
            .append(run("[Scan coast+rivers]", "/osm coast")).append(" ")
            .append(suggest("[Start line]", "/osm line start ")).append(" ")
            .append(run("[End line]", "/osm line end")).append(" ")
            .append(run("[Export]", "/osm export world")).append(" ")
            .append(run("[Status]", "/osm status"));
        src.sendSuccess(() -> m, false);
        return 1;
    }

    // ---- commands
    private static int coast(CommandContext<CommandSourceStack> c, int radius) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ServerLevel level = (ServerLevel) p.level();
        if (level.dimension() != Level.OVERWORLD) {
            c.getSource().sendFailure(Component.literal("Only the Overworld can be scanned."));
            return 0;
        }
        int n = OsmData.scan(level, p.blockPosition().getX() >> 4, p.blockPosition().getZ() >> 4, radius);
        say(c, "Scanned " + n + " loaded chunks (" + OsmData.CELLS.size() + " total). Walk elsewhere and scan again to add more.");
        return n;
    }

    private static int lineStart(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        if (OsmData.ACTIVE.containsKey(p.getUUID())) {
            c.getSource().sendFailure(Component.literal("A line is already active. Use /osm line end."));
            return 0;
        }
        OsmData.Line l = new OsmData.Line(StringArgumentType.getString(c, "name").trim());
        l.pts.add(new double[] {p.getX(), p.getZ()});
        OsmData.ACTIVE.put(p.getUUID(), l);
        c.getSource().sendSuccess(() -> Component.literal("Recording \"" + l.name + "\". Walk the path, then ")
            .append(run("[End line]", "/osm line end")), false);
        return 1;
    }

    private static int lineEnd(CommandContext<CommandSourceStack> c, boolean keep) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        OsmData.Line l = OsmData.ACTIVE.remove(p.getUUID());
        if (l == null) {
            c.getSource().sendFailure(Component.literal("No active line."));
            return 0;
        }
        if (!keep) { say(c, "Line discarded."); return 1; }
        l.pts.add(new double[] {p.getX(), p.getZ()});
        if (l.pts.size() < 2) { say(c, "Line too short, discarded."); return 0; }
        OsmData.LINES.add(l);
        say(c, "Saved \"" + l.name + "\" (" + l.pts.size() + " points).");
        return 1;
    }

    private static int export(CommandContext<CommandSourceStack> c, String name) {
        try {
            var f = OsmExporter.export(name.replaceAll("[^A-Za-z0-9_-]", "_"));
            say(c, "Exported to " + f.toAbsolutePath());
            return 1;
        } catch (Exception e) {
            c.getSource().sendFailure(Component.literal("Export failed: " + e));
            return 0;
        }
    }
}
