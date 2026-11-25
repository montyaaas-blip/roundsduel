package cz.minihry.roundduel;

import org.bukkit.Location;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class RoundDuelPlugin extends JavaPlugin {

    private Game game;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        game = new Game(this);
        getServer().getPluginManager().registerEvents(new GameListener(this, game), this);

        PluginCommand command = getCommand("roundduel");
        if (command != null) {
            RoundDuelCommand handler = new RoundDuelCommand(this, game);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
    }

    @Override
    public void onDisable() {
        if (game != null) {
            game.shutdown();
        }
    }

    // ---- Uložená aréna (lobby a spawny obou týmů) ----

    public Location getLobby() {
        return getConfig().getLocation("arena.lobby");
    }

    public void setLobby(Location location) {
        getConfig().set("arena.lobby", location);
        saveConfig();
    }

    public List<Location> getSpawns(Team team) {
        List<Location> result = new ArrayList<>();
        for (Object o : getConfig().getList("arena." + team.key() + ".spawns", List.of())) {
            if (o instanceof Location location) {
                result.add(location);
            }
        }
        return result;
    }

    public void addSpawn(Team team, Location location) {
        List<Location> spawns = getSpawns(team);
        spawns.add(location);
        getConfig().set("arena." + team.key() + ".spawns", spawns);
        saveConfig();
    }

    public void clearSpawns(Team team) {
        getConfig().set("arena." + team.key() + ".spawns", new ArrayList<Location>());
        saveConfig();
    }

    public boolean isArenaReady() {
        return !getSpawns(Team.RED).isEmpty() && !getSpawns(Team.BLUE).isEmpty();
    }
}
