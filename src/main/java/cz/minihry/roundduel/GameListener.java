package cz.minihry.roundduel;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public final class GameListener implements Listener {

    private final RoundDuelPlugin plugin;
    private final Game game;

    public GameListener(RoundDuelPlugin plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
    }

    // ------------------------------------------------------------------
    // PvP: jen v běžícím kole, jen mezi různými týmy, jen mezi živými
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !game.isPlaying(victim)) {
            return;
        }
        if (!game.isCombatAllowed() || victim.getGameMode() == GameMode.SPECTATOR || !game.isAlive(victim)) {
            event.setCancelled(true);
            return;
        }
        Player attacker = attackerOf(event);
        if (attacker != null && game.isPlaying(attacker) && game.teamOf(attacker) == game.teamOf(victim)) {
            event.setCancelled(true); // přátelská palba
        }
    }

    private Player attackerOf(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    /** Žádné přirozené doléčení - zásahy musí bolet celé kolo. */
    @EventHandler(ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent event) {
        if (event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED
                && event.getEntity() instanceof Player player && game.isPlaying(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && game.isPlaying(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL
                && event.getEntity() instanceof Player player && game.isPlaying(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (game.isPlaying(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (game.isPlaying(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (game.isPlaying(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    // Smrt: žádný respawn, jen divák do konce kola
    // ------------------------------------------------------------------

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (!game.isPlaying(victim)) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.deathMessage(null);
        game.handleDeath(victim, victim.getKiller());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!game.isPlaying(player)) {
            return;
        }
        event.setRespawnLocation(game.spectatorLocation());
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                game.afterRespawn(player);
            }
        }, 2L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        game.leave(event.getPlayer(), false);
    }
}
