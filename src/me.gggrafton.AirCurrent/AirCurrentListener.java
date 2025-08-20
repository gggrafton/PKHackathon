package me.gggrafton.AirCurrent;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.entity.Player;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.CoreAbility;

public class AirCurrentListener implements Listener {

    // Left-click: if gliding -> BOOST, else -> CLUSTER (start/launch)
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onLeftClick(final PlayerAnimationEvent event) {
        final Player player = event.getPlayer();
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (bPlayer == null) return;

        final AirCurrent active = CoreAbility.getAbility(player, AirCurrent.class);

        if (player.isGliding()) {
            // Elytra BOOST on left-click while gliding
            if (active != null) {
                // Will flip to boost-only internally and prevent cluster from running
                active.startBoost();
            } else if (bPlayer.canBendIgnoreCooldowns(CoreAbility.getAbility(AirCurrent.NAME))) {
                new AirCurrent(player, true); // boost-only instance
            }
            return;
        }

        // Not gliding -> CLUSTER behavior
        if (active == null) {
            if (bPlayer.canBend(CoreAbility.getAbility(AirCurrent.NAME))) {
                new AirCurrent(player); // start cluster
            }
        } else {
            active.launch(); // second left-click = launch cluster
        }
    }

    // Sneak to set origin (AirBlast-style)
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSneak(final PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return;

        final Player player = event.getPlayer();
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (bPlayer == null) return;

        if (CoreAbility.getAbility(player, AirCurrent.class) == null
                && bPlayer.canBend(CoreAbility.getAbility(AirCurrent.NAME))) {
            AirCurrent.setOrigin(player);
        }
    }
}
