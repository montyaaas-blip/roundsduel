package cz.minihry.roundduel;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RoundDuelCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN = "roundduel.admin";

    private final RoundDuelPlugin plugin;
    private final Game game;

    public RoundDuelCommand(RoundDuelPlugin plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "join" -> {
                if (requirePlayer(sender)) {
                    game.join((Player) sender);
                }
            }
            case "leave" -> {
                if (requirePlayer(sender)) {
                    Player player = (Player) sender;
                    if (!game.leave(player, true)) {
                        game.send(player, "<red>Nejsi ve hře.");
                    }
                }
            }
            case "status" -> status(sender);
            case "start" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                if (game.forceStart()) {
                    game.send(sender, "<green>Hra se spouští.");
                } else {
                    game.send(sender, "<red>Nelze spustit (hra už běží, nebo chybí hráči aspoň v obou týmech).");
                }
            }
            case "stop" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                if (game.stop()) {
                    game.send(sender, "<green>Hra zastavena.");
                } else {
                    game.send(sender, "<red>Žádná hra neběží.");
                }
            }
            case "setlobby" -> {
                if (!requireAdmin(sender) || !requirePlayer(sender)) {
                    return true;
                }
                plugin.setLobby(((Player) sender).getLocation());
                game.send(sender, "<green>Lobby nastaveno na tvoji pozici.");
            }
            case "setspawn" -> {
                Team team = requireTeamArg(sender, args, 1);
                if (team == null || !requireAdmin(sender) || !requirePlayer(sender)) {
                    return true;
                }
                plugin.addSpawn(team, ((Player) sender).getLocation());
                game.send(sender, "<green>Spawn týmu <white><t></white> přidán (celkem <white><n></white>).",
                        Placeholder.unparsed("t", team.displayName()),
                        Placeholder.unparsed("n", String.valueOf(plugin.getSpawns(team).size())));
            }
            case "clearspawns" -> {
                Team team = requireTeamArg(sender, args, 1);
                if (team == null || !requireAdmin(sender)) {
                    return true;
                }
                plugin.clearSpawns(team);
                game.send(sender, "<green>Spawny týmu <white><t></white> smazány.",
                        Placeholder.unparsed("t", team.displayName()));
            }
            case "arena" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                game.send(sender, "<white>Červení:</white> <gray>spawnů <white><n></white>",
                        Placeholder.unparsed("n", String.valueOf(plugin.getSpawns(Team.RED).size())));
                game.send(sender, "<white>Modří:</white> <gray>spawnů <white><n></white>",
                        Placeholder.unparsed("n", String.valueOf(plugin.getSpawns(Team.BLUE).size())));
                game.send(sender, "<gray>Lobby: <white><l></white>",
                        Placeholder.unparsed("l", plugin.getLobby() != null ? "nastaveno" : "nenastaveno"));
            }
            default -> help(sender);
        }
        return true;
    }

    private void status(CommandSender sender) {
        String state = switch (game.getState()) {
            case WAITING -> "čeká se na hráče";
            case COUNTDOWN -> "odpočet před zápasem";
            case ROUND_COUNTDOWN -> "odpočet před kolem";
            case ROUND_RUNNING -> "kolo probíhá";
            case ROUND_END -> "konec kola";
            case MATCH_END -> "konec zápasu";
        };
        game.send(sender, "Stav: <white><s></white>", Placeholder.unparsed("s", state));
        if (sender instanceof Player player && game.isPlaying(player)) {
            Team team = game.teamOf(player);
            game.send(sender, "Tvůj tým: <white><t></white>",
                    Placeholder.unparsed("t", team != null ? team.displayName() : "-"));
        }
    }

    private void help(CommandSender sender) {
        game.send(sender, "<white>/roundduel join</white> - připojit se do hry");
        game.send(sender, "<white>/roundduel leave</white> - odejít ze hry");
        game.send(sender, "<white>/roundduel status</white> - stav hry");
        if (sender.hasPermission(ADMIN)) {
            game.send(sender, "<white>/roundduel setlobby</white> - nastaví čekárnu na tvoji pozici");
            game.send(sender, "<white>/roundduel setspawn [tým]</white> - přidá spawn týmu na tvoji pozici");
            game.send(sender, "<white>/roundduel clearspawns [tým]</white> - smaže spawny týmu");
            game.send(sender, "<white>/roundduel arena</white> - stav nastavení arény");
            game.send(sender, "<white>/roundduel start</white> - vynutí start i s menším počtem hráčů");
            game.send(sender, "<white>/roundduel stop</white> - zruší hru");
        }
    }

    private Team requireTeamArg(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            game.send(sender, "<red>Zadej tým: red nebo blue.");
            return null;
        }
        Team team = Team.byKey(args[index]);
        if (team == null) {
            game.send(sender, "<red>Neznámý tým. Použij: red nebo blue.");
        }
        return team;
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission(ADMIN)) {
            return true;
        }
        game.send(sender, "<red>Na to nemáš oprávnění.");
        return false;
    }

    private boolean requirePlayer(CommandSender sender) {
        if (sender instanceof Player) {
            return true;
        }
        game.send(sender, "<red>Tento příkaz může použít jen hráč.");
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        boolean admin = sender.hasPermission(ADMIN);

        if (args.length == 1) {
            options.addAll(List.of("join", "leave", "status"));
            if (admin) {
                options.addAll(List.of("setlobby", "setspawn", "clearspawns", "arena", "start", "stop"));
            }
        } else if (args.length == 2 && admin
                && (args[0].equalsIgnoreCase("setspawn") || args[0].equalsIgnoreCase("clearspawns"))) {
            for (Team team : Team.values()) {
                options.add(team.key());
            }
        } else {
            return List.of();
        }

        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
