package cz.minihry.roundduel;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Jediná aréna RoundDuel. 2 týmy (max 5 a 5) hrají na kola bez respawnu v kole -
 * kdo zemře, divá se dál jako divák. Kolo vyhrává tým, kterému zůstane poslední živý
 * hráč. Zápas vyhrává tým, co první vyhraje nastavený počet kol.
 */
public final class Game {

    public enum State { WAITING, COUNTDOWN, ROUND_COUNTDOWN, ROUND_RUNNING, ROUND_END, MATCH_END }

    private record Snapshot(Location location, ItemStack[] contents, GameMode gameMode,
                            double health, int food, float saturation,
                            List<PotionEffect> effects, Scoreboard scoreboard) {}

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<gradient:#ff4d4d:#4d94ff>⚔ RoundDuel</gradient> <dark_gray>» <gray>";
    private static final int ENDING_SECONDS = 8;

    private final RoundDuelPlugin plugin;
    private final Map<UUID, Snapshot> snapshots = new HashMap<>();
    private final Map<UUID, Team> playerTeam = new HashMap<>();
    private final Scoreboard scoreboard;
    private final Objective objective;
    private final BossBar bossBar =
            BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
    private final BukkitTask ticker;

    private State state = State.WAITING;
    private boolean forced;
    private int secondsLeft;
    private int countdownTotal;
    private int roundTimeLimit;
    private int roundNumber;

    private final Map<Team, Integer> scores = new EnumMap<>(Team.class);
    private final Set<UUID> aliveThisRound = new HashSet<>();

    public Game(RoundDuelPlugin plugin) {
        this.plugin = plugin;
        this.scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        this.objective = scoreboard.registerNewObjective(
                "roundduel", Criteria.DUMMY, MM.deserialize("<red><bold>⚔ RoundDuel ⚔"));
        this.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        this.ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        updateBossBar();
    }

    // ------------------------------------------------------------------
    // Veřejné API
    // ------------------------------------------------------------------

    public State getState() {
        return state;
    }

    public boolean isPlaying(Player player) {
        return playerTeam.containsKey(player.getUniqueId());
    }

    public Team teamOf(Player player) {
        return playerTeam.get(player.getUniqueId());
    }

    public boolean isAlive(Player player) {
        return aliveThisRound.contains(player.getUniqueId());
    }

    public boolean isCombatAllowed() {
        return state == State.ROUND_RUNNING;
    }

    public void send(CommandSender to, String text, TagResolver... resolvers) {
        to.sendMessage(msg(text, resolvers));
    }

    public void join(Player player) {
        if (isPlaying(player)) {
            send(player, "<red>Už ve hře jsi.");
            return;
        }
        if (state != State.WAITING && state != State.COUNTDOWN) {
            send(player, "<red>Zápas právě probíhá, zkus to po jeho skončení.");
            return;
        }
        if (!plugin.isArenaReady()) {
            send(player, "<red>Aréna ještě není nastavená (chybí spawny některého týmu).");
            return;
        }
        Team team = smallestTeam();
        if (team == null) {
            send(player, "<red>Oba týmy jsou plné.");
            return;
        }

        snapshots.put(player.getUniqueId(), takeSnapshot(player));
        playerTeam.put(player.getUniqueId(), team);
        preparePlayer(player);

        Location lobby = plugin.getLobby();
        if (lobby != null) {
            player.teleport(lobby);
        }
        player.setScoreboard(scoreboard);
        player.showBossBar(bossBar);

        broadcast("<white><player></white> se připojil do týmu <white><team></white> <gray>(<n>)",
                ph("player", player.getName()), ph("team", team.displayName()), ph("n", playerTeam.size()));

        tryAutoStart();
        updateBossBar();
        refreshSidebar();
    }

    public boolean leave(Player player, boolean notify) {
        UUID id = player.getUniqueId();
        Team team = playerTeam.remove(id);
        if (team == null) {
            return false;
        }
        aliveThisRound.remove(id);
        player.hideBossBar(bossBar);

        Snapshot snapshot = snapshots.remove(id);
        if (snapshot != null) {
            restore(player, snapshot);
        }
        if (notify) {
            send(player, "<yellow>Opustil jsi hru.");
        }
        broadcast("<white><player></white> <yellow>odešel ze hry.", ph("player", player.getName()));

        if (playerTeam.isEmpty()) {
            resetGame();
        } else if (state == State.WAITING || state == State.COUNTDOWN) {
            if (!forced && (playerTeam.size() < plugin.getConfig().getInt("min-players", 2)
                    || countTeamsWithPlayers() < 2)) {
                state = State.WAITING;
            }
        } else {
            // Hráč odešel uprostřed zápasu - pokud jeho tým nemá vůbec nikoho, zápas končí.
            if (teamSize(team) == 0) {
                Team other = team.other();
                broadcast("<yellow>Tým <white><team></white> přišel o všechny hráče.", ph("team", team.displayName()));
                endMatch(other);
                return true;
            }
            if (state == State.ROUND_RUNNING) {
                checkRoundOutcome();
            }
        }
        refreshSidebar();
        return true;
    }

    public boolean forceStart() {
        if ((state != State.WAITING && state != State.COUNTDOWN) || countTeamsWithPlayers() < 2) {
            return false;
        }
        forced = true;
        if (state == State.WAITING) {
            startCountdown(true);
        }
        secondsLeft = Math.min(secondsLeft, 5);
        updateBossBar();
        return true;
    }

    public boolean stop() {
        if (state == State.WAITING && playerTeam.isEmpty()) {
            return false;
        }
        broadcast("<red>Hra byla zrušena adminem.");
        resetGame();
        return true;
    }

    public void shutdown() {
        ticker.cancel();
        resetGame();
    }

    /** Volá listener při smrti hráče. */
    public void handleDeath(Player victim, Player killer) {
        if (state != State.ROUND_RUNNING || !isPlaying(victim)) {
            return;
        }
        aliveThisRound.remove(victim.getUniqueId());
        if (killer != null && isPlaying(killer) && !killer.equals(victim)) {
            broadcast("<white><killer></white> <gray>eliminoval <white><victim></white>",
                    ph("killer", killer.getName()), ph("victim", victim.getName()));
        } else {
            broadcast("<white><victim></white> <gray>byl vyřazen.", ph("victim", victim.getName()));
        }
        refreshSidebar();
        checkRoundOutcome();
    }

    /** Kam poslat hráče po "respawnu" (ve skutečnosti jen přechod do diváka). */
    public Location spectatorLocation() {
        Location lobby = plugin.getLobby();
        return lobby != null ? lobby : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    public void afterRespawn(Player player) {
        if (!isPlaying(player)) {
            return;
        }
        player.setGameMode(GameMode.SPECTATOR);
    }

    // ------------------------------------------------------------------
    // Průběh hry
    // ------------------------------------------------------------------

    private void tick() {
        switch (state) {
            case WAITING -> { }
            case COUNTDOWN -> tickCountdown();
            case ROUND_COUNTDOWN -> tickRoundCountdown();
            case ROUND_RUNNING -> tickRoundRunning();
            case ROUND_END -> tickRoundEnd();
            case MATCH_END -> tickMatchEnd();
        }
    }

    private void tryAutoStart() {
        if (state == State.WAITING
                && playerTeam.size() >= plugin.getConfig().getInt("min-players", 2)
                && countTeamsWithPlayers() >= 2) {
            startCountdown(false);
        }
    }

    private void startCountdown(boolean force) {
        state = State.COUNTDOWN;
        forced = force;
        countdownTotal = plugin.getConfig().getInt("countdown-seconds", 20);
        secondsLeft = countdownTotal;
        broadcast("<green>Zápas brzy začne!");
    }

    private void tickCountdown() {
        if (!forced && (playerTeam.size() < plugin.getConfig().getInt("min-players", 2)
                || countTeamsWithPlayers() < 2)) {
            state = State.WAITING;
            broadcast("<yellow>Málo hráčů, čekám na další...");
            updateBossBar();
            return;
        }
        if (secondsLeft <= 0) {
            startMatch();
            return;
        }
        if (secondsLeft <= 5 || secondsLeft % 10 == 0) {
            for (Player p : onlinePlayers()) {
                p.sendActionBar(MM.deserialize("<green>Start za <white><n></white> s", ph("n", secondsLeft)));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
            }
        }
        secondsLeft--;
        updateBossBar();
    }

    private void startMatch() {
        scores.put(Team.RED, 0);
        scores.put(Team.BLUE, 0);
        roundNumber = 0;
        broadcast("<red>Zápas začíná! <gray>Hraje se do <white><n></white> vyhraných kol.",
                ph("n", plugin.getConfig().getInt("rounds-to-win", 5)));
        startRound();
    }

    private void startRound() {
        roundNumber++;
        aliveThisRound.clear();
        for (Player p : onlinePlayers()) {
            Team team = teamOf(p);
            if (team == null) {
                continue;
            }
            aliveThisRound.add(p.getUniqueId());
            List<Location> spawns = plugin.getSpawns(team);
            if (!spawns.isEmpty()) {
                p.teleport(spawns.get(ThreadLocalRandom.current().nextInt(spawns.size())));
            }
            equip(p, team);
            p.setHealth(maxHealth(p));
            p.setGameMode(GameMode.SURVIVAL);
            for (PotionEffect effect : p.getActivePotionEffects()) {
                p.removePotionEffect(effect.getType());
            }
            p.showTitle(Title.title(
                    MM.deserialize("<yellow><bold>Kolo <n>", ph("n", roundNumber)),
                    MM.deserialize("<gray>Připrav se..."),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(400))));
        }

        state = State.ROUND_COUNTDOWN;
        secondsLeft = plugin.getConfig().getInt("round-countdown-seconds", 5);
        updateBossBar();
        refreshSidebar();
    }

    private void tickRoundCountdown() {
        if (secondsLeft <= 0) {
            state = State.ROUND_RUNNING;
            roundTimeLimit = plugin.getConfig().getInt("round-time-limit-seconds", 90);
            secondsLeft = roundTimeLimit;
            for (Player p : onlinePlayers()) {
                p.showTitle(Title.title(MM.deserialize("<red><bold>Boj!"), Component.empty(),
                        Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(800), Duration.ofMillis(300))));
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.3f);
            }
            updateBossBar();
            return;
        }
        for (Player p : onlinePlayers()) {
            p.sendActionBar(MM.deserialize("<yellow>Kolo začíná za <white><n></white> s", ph("n", secondsLeft)));
        }
        secondsLeft--;
        updateBossBar();
    }

    private void tickRoundRunning() {
        for (Player p : onlinePlayers()) {
            if (isAlive(p)) {
                keepInArena(p);
            }
        }
        if (roundTimeLimit > 0) {
            secondsLeft--;
            if (secondsLeft <= 0) {
                broadcast("<yellow>Čas kola vypršel, hraje se remíza - kolo se opakuje.");
                endRound(null);
                return;
            }
        }
        updateBossBar();
    }

    private void checkRoundOutcome() {
        if (state != State.ROUND_RUNNING) {
            return;
        }
        boolean redAlive = aliveCount(Team.RED) > 0;
        boolean blueAlive = aliveCount(Team.BLUE) > 0;
        if (redAlive && blueAlive) {
            return;
        }
        if (!redAlive && !blueAlive) {
            endRound(null);
        } else {
            endRound(redAlive ? Team.RED : Team.BLUE);
        }
    }

    private void endRound(Team winner) {
        state = State.ROUND_END;
        secondsLeft = plugin.getConfig().getInt("round-end-pause-seconds", 4);

        if (winner != null) {
            scores.merge(winner, 1, Integer::sum);
            broadcast("<white><team></white> <gray>vyhrál kolo! <gray>Skóre: <white><red></white> : <white><blue></white>",
                    ph("team", winner.displayName()),
                    ph("red", scores.getOrDefault(Team.RED, 0)), ph("blue", scores.getOrDefault(Team.BLUE, 0)));
            for (Player p : onlinePlayers()) {
                boolean won = winner.equals(teamOf(p));
                p.showTitle(Title.title(
                        won ? MM.deserialize("<green><bold>Kolo vyhráno!") : MM.deserialize("<red><bold>Kolo prohráno"),
                        Component.empty(),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(400))));
            }
        }

        int roundsToWin = plugin.getConfig().getInt("rounds-to-win", 5);
        if (winner != null && scores.getOrDefault(winner, 0) >= roundsToWin) {
            endMatch(winner);
        }
        refreshSidebar();
        updateBossBar();
    }

    private void tickRoundEnd() {
        if (secondsLeft <= 0) {
            startRound();
            return;
        }
        secondsLeft--;
        updateBossBar();
    }

    private void endMatch(Team winner) {
        state = State.MATCH_END;
        secondsLeft = ENDING_SECONDS;
        broadcast("<gold><bold>Zápas vyhrál tým <white><team></white>!", ph("team", winner.displayName()));
        for (Player p : onlinePlayers()) {
            boolean won = winner.equals(teamOf(p));
            p.setGameMode(GameMode.SURVIVAL);
            p.showTitle(Title.title(
                    won ? MM.deserialize("<gold><bold>VÍTĚZSTVÍ!") : MM.deserialize("<red><bold>PROHRA"),
                    MM.deserialize("<gray>Tým <white><team></white> vyhrál zápas", ph("team", winner.displayName())),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(500))));
            p.playSound(p.getLocation(), won ? Sound.ENTITY_PLAYER_LEVELUP : Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
        }
        updateBossBar();
        refreshSidebar();
    }

    private void tickMatchEnd() {
        secondsLeft--;
        for (Player p : onlinePlayers()) {
            if (teamOf(p) != null && isAlive(p)) {
                spawnFirework(p.getLocation());
            }
        }
        if (secondsLeft <= 0) {
            resetGame();
        }
    }

    /** Vrátí všechny hráče, vyčistí stav a připraví hru na další zápas. */
    private void resetGame() {
        for (UUID id : new ArrayList<>(playerTeam.keySet())) {
            Player p = Bukkit.getPlayer(id);
            Snapshot snapshot = snapshots.get(id);
            if (p != null) {
                p.hideBossBar(bossBar);
                if (snapshot != null) {
                    restore(p, snapshot);
                }
            }
        }
        playerTeam.clear();
        snapshots.clear();
        aliveThisRound.clear();
        scores.clear();
        for (String entry : new ArrayList<>(scoreboard.getEntries())) {
            scoreboard.resetScores(entry);
        }
        forced = false;
        roundNumber = 0;
        state = State.WAITING;
        updateBossBar();
    }

    // ------------------------------------------------------------------
    // Výzbroj a pomocné metody
    // ------------------------------------------------------------------

    private void equip(Player player, Team team) {
        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setHelmet(leather(Material.LEATHER_HELMET, team));
        inv.setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
        inv.setLeggings(new ItemStack(Material.CHAINMAIL_LEGGINGS));
        inv.setBoots(leather(Material.LEATHER_BOOTS, team));
        inv.setItem(0, new ItemStack(Material.IRON_SWORD));
        inv.setItem(1, new ItemStack(Material.BOW));
        inv.setItem(2, new ItemStack(Material.ARROW, plugin.getConfig().getInt("arrows", 16)));
    }

    private ItemStack leather(Material material, Team team) {
        ItemStack item = new ItemStack(material);
        item.editMeta(LeatherArmorMeta.class, meta -> meta.setColor(team.armorColor()));
        return item;
    }

    private void keepInArena(Player p) {
        Team team = teamOf(p);
        List<Location> spawns = plugin.getSpawns(team);
        if (spawns.isEmpty()) {
            return;
        }
        Location center = spawns.get(0);
        double radius = plugin.getConfig().getDouble("arena-radius", 100.0);
        Location loc = p.getLocation();
        if (!loc.getWorld().equals(center.getWorld()) || loc.distanceSquared(center) > radius * radius) {
            p.teleport(spawns.get(ThreadLocalRandom.current().nextInt(spawns.size())));
            p.sendActionBar(MM.deserialize("<yellow>Vrátil ses zpět do arény."));
        }
    }

    private int aliveCount(Team team) {
        int count = 0;
        for (UUID id : aliveThisRound) {
            if (team.equals(playerTeam.get(id))) {
                count++;
            }
        }
        return count;
    }

    private int teamSize(Team team) {
        int count = 0;
        for (Team t : playerTeam.values()) {
            if (t == team) {
                count++;
            }
        }
        return count;
    }

    private Team smallestTeam() {
        int max = plugin.getConfig().getInt("max-per-team", 5);
        int red = teamSize(Team.RED);
        int blue = teamSize(Team.BLUE);
        if (red <= blue && red < max) {
            return Team.RED;
        }
        if (blue < max) {
            return Team.BLUE;
        }
        return red < max ? Team.RED : null;
    }

    private int countTeamsWithPlayers() {
        Set<Team> seen = EnumSet.noneOf(Team.class);
        for (Team t : playerTeam.values()) {
            seen.add(t);
        }
        return seen.size();
    }

    private void spawnFirework(Location location) {
        location.getWorld().spawn(location.clone().add(0, 1, 0), Firework.class, firework -> {
            FireworkMeta meta = firework.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .with(FireworkEffect.Type.STAR)
                    .withColor(Color.YELLOW, Color.WHITE)
                    .withFade(Color.ORANGE)
                    .build());
            meta.setPower(1);
            firework.setFireworkMeta(meta);
        });
    }

    private Snapshot takeSnapshot(Player p) {
        ItemStack[] raw = p.getInventory().getContents();
        ItemStack[] copy = new ItemStack[raw.length];
        for (int i = 0; i < raw.length; i++) {
            copy[i] = raw[i] == null ? null : raw[i].clone();
        }
        return new Snapshot(p.getLocation(), copy, p.getGameMode(), p.getHealth(), p.getFoodLevel(),
                p.getSaturation(), new ArrayList<>(p.getActivePotionEffects()), p.getScoreboard());
    }

    private void preparePlayer(Player p) {
        p.getInventory().clear();
        p.setGameMode(GameMode.ADVENTURE);
        p.setHealth(maxHealth(p));
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        for (PotionEffect effect : p.getActivePotionEffects()) {
            p.removePotionEffect(effect.getType());
        }
    }

    private void restore(Player p, Snapshot s) {
        p.teleport(s.location());
        p.getInventory().clear();
        p.getInventory().setContents(s.contents());
        for (PotionEffect effect : p.getActivePotionEffects()) {
            p.removePotionEffect(effect.getType());
        }
        for (PotionEffect effect : s.effects()) {
            p.addPotionEffect(effect);
        }
        p.setGameMode(s.gameMode());
        p.setHealth(Math.min(s.health(), maxHealth(p)));
        p.setFoodLevel(s.food());
        p.setSaturation(s.saturation());
        p.setScoreboard(s.scoreboard());
    }

    private double maxHealth(Player p) {
        var attribute = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        return attribute != null ? attribute.getValue() : 20.0;
    }

    // ------------------------------------------------------------------
    // Scoreboard
    // ------------------------------------------------------------------

    private void refreshSidebar() {
        for (String entry : new ArrayList<>(scoreboard.getEntries())) {
            scoreboard.resetScores(entry);
        }
        int line = 20;
        line = sidebarTeam(Team.RED, line);
        objective.getScore(spacer(0)).setScore(line--);
        line = sidebarTeam(Team.BLUE, line);
    }

    private int sidebarTeam(Team team, int line) {
        int score = scores.getOrDefault(team, 0);
        objective.getScore("§" + (team == Team.RED ? "c" : "9") + "§l" + team.displayName() + ": " + score)
                .setScore(line--);
        for (Map.Entry<UUID, Team> entry : playerTeam.entrySet()) {
            if (entry.getValue() != team) {
                continue;
            }
            Player p = Bukkit.getPlayer(entry.getKey());
            String name = p != null ? p.getName() : "?";
            boolean inRoundPhase = state == State.ROUND_COUNTDOWN || state == State.ROUND_RUNNING
                    || state == State.ROUND_END;
            boolean alive = !inRoundPhase || aliveThisRound.contains(entry.getKey());
            String text = alive ? "§a" + name : "§7§m" + name;
            objective.getScore(text).setScore(line--);
        }
        return line;
    }

    private String spacer(int index) {
        String[] codes = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "d", "e", "f"};
        return "§" + codes[index % codes.length] + "§r";
    }

    private void updateBossBar() {
        switch (state) {
            case WAITING -> {
                bossBar.name(MM.deserialize("<white>Čekání na hráče <gray>(<n>/<min>, oba týmy)",
                        ph("n", playerTeam.size()), ph("min", plugin.getConfig().getInt("min-players", 2))));
                bossBar.color(BossBar.Color.WHITE);
                bossBar.progress(1f);
            }
            case COUNTDOWN -> {
                bossBar.name(MM.deserialize("<green>Start za <white><n></white> s", ph("n", secondsLeft)));
                bossBar.color(BossBar.Color.GREEN);
                bossBar.progress(clamp(countdownTotal == 0 ? 0f : (float) secondsLeft / countdownTotal));
            }
            case ROUND_COUNTDOWN -> {
                bossBar.name(MM.deserialize("<yellow>Kolo <n> za <white><s></white> s",
                        ph("n", roundNumber), ph("s", secondsLeft)));
                bossBar.color(BossBar.Color.YELLOW);
                bossBar.progress(1f);
            }
            case ROUND_RUNNING -> {
                bossBar.name(MM.deserialize("<red>Kolo <n> <gray>| <white><red></white>:<white><blue></white> <gray>| <white><s></white>",
                        ph("n", roundNumber), ph("red", scores.getOrDefault(Team.RED, 0)),
                        ph("blue", scores.getOrDefault(Team.BLUE, 0)),
                        ph("s", roundTimeLimit > 0 ? secondsLeft + "s" : "∞")));
                bossBar.color(BossBar.Color.RED);
                bossBar.progress(roundTimeLimit > 0 ? clamp((float) secondsLeft / roundTimeLimit) : 1f);
            }
            case ROUND_END -> {
                bossBar.name(MM.deserialize("<gold>Další kolo za <white><n></white> s", ph("n", secondsLeft)));
                bossBar.color(BossBar.Color.YELLOW);
                bossBar.progress(1f);
            }
            case MATCH_END -> {
                bossBar.name(MM.deserialize("<gold>Konec zápasu"));
                bossBar.color(BossBar.Color.YELLOW);
                bossBar.progress(1f);
            }
        }
    }

    private List<Player> onlinePlayers() {
        List<Player> list = new ArrayList<>();
        for (UUID id : playerTeam.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                list.add(p);
            }
        }
        return list;
    }

    private void broadcast(String text, TagResolver... resolvers) {
        Component component = msg(text, resolvers);
        for (Player p : onlinePlayers()) {
            p.sendMessage(component);
        }
    }

    private Component msg(String text, TagResolver... resolvers) {
        return MM.deserialize(PREFIX + text, resolvers);
    }

    private static TagResolver ph(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
