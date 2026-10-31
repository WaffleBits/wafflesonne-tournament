package com.wafflesonne.tournamentplugin.tournament;

import com.wafflesonne.tournamentplugin.WaffleTournament;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;

/** Axis-aligned arena box plus the spawn ring players are placed on. */
public record Arena(World world,
                    double minX, double maxX,
                    double minY, double maxY,
                    double minZ, double maxZ,
                    double centreX, double centreZ,
                    double spawnY, double spawnRing) {

    public static Arena fromConfig(WaffleTournament plugin) {
        var c = plugin.getConfig();
        String worldName = c.getString("tournament.arena.world", "empty");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("Arena world '" + worldName + "' is not loaded");
            return null;
        }
        return new Arena(world,
            c.getDouble("tournament.arena.min-x"), c.getDouble("tournament.arena.max-x"),
            c.getDouble("tournament.arena.min-y"), c.getDouble("tournament.arena.max-y"),
            c.getDouble("tournament.arena.min-z"), c.getDouble("tournament.arena.max-z"),
            c.getDouble("tournament.arena.center-x"), c.getDouble("tournament.arena.center-z"),
            c.getDouble("tournament.arena.spawn-y"), c.getDouble("tournament.arena.spawn-ring"));
    }

    public boolean contains(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        if (!loc.getWorld().equals(world)) return false;
        return loc.getX() >= minX && loc.getX() <= maxX
            && loc.getY() >= minY && loc.getY() <= maxY
            && loc.getZ() >= minZ && loc.getZ() <= maxZ;
    }

    /**
     * Place players evenly around the spawn ring facing the centre.
     *
     * <p>The Skript version cycled four fixed points, so a fifth player spawned
     * inside a fourth. Spacing by participant count keeps everyone apart.
     */
    public void spreadOnRing(List<Player> players) {
        if (players.isEmpty()) return;
        double step = (2 * Math.PI) / players.size();
        for (int i = 0; i < players.size(); i++) {
            double angle = step * i;
            double x = centreX + spawnRing * Math.cos(angle);
            double z = centreZ + spawnRing * Math.sin(angle);
            Location loc = new Location(world, x + 0.5, spawnY, z + 0.5);
            // Face the middle of the arena.
            loc.setYaw((float) (Math.toDegrees(Math.atan2(centreZ - z, centreX - x)) - 90));
            players.get(i).teleport(loc);
        }
    }
}
