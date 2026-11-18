package org.Cry_Legende.sHItemTags;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import net.kyori.adventure.key.Key;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aveugle le joueur (effet Cécité) tant qu'un dialogue est affiché : le texte se lit sur fond noir au lieu du décor. Ferme
 * aussi les dialogues gardés ouverts au clic ({@link #stayOpen}) quand aucun autre ne les remplace.
 * <p>
 * Un dialogue dont un bouton peut rouvrir un dialogue (erreur de saisie, aperçu, écran suivant) est construit avec
 * {@link #stayOpen} : le client le garde à l'écran après le clic et le dialogue suivant le remplace directement, sans repasser
 * par le jeu (pas de clignotement). Si aucun dialogue ne suit dans les 4 ticks, cette classe le ferme ; un traitement plus long
 * (base de données, réseau) appelle {@link #awaitReply} pour qu'il reste affiché jusqu'à sa réponse.
 * <p>
 * Fermeture détectée par un clic : tout bouton à action serveur (custom) prévient le serveur. Un bouton qui ferme simplement
 * le dialogue porte {@link #closeAction()} : le dialogue est fermé et la vue rendue tout de suite. Mis sur le bouton du bas
 * (exit_action), il sert aussi à Échap, que le client traduit par l'action de ce bouton. Après un autre bouton, le dialogue est
 * fermé et la vue rendue si aucun nouveau dialogue ne s'est ouvert dans les 4 ticks.
 * <p>
 * Sinon (bouton de lien, Échap sans bouton du bas, bouton sans action), le client ne dit rien : le dialogue est considéré comme
 * fermé dès que le joueur tourne la caméra, appuie sur une touche de déplacement ou ouvre un inventaire, ce qui est impossible
 * tant qu'un dialogue est à l'écran. Les mouvements reçus juste après l'ouverture (paquets déjà en route, 400 ms plus deux
 * fois le ping) sont ignorés.
 * <p>
 * Les plugins SkyHand qui affichent des dialogues ont chacun cette classe. L'heure du dernier dialogue affiché est gardée sur le
 * joueur sous la même clé pour tous (skyhand:dialogue_affiche) : un plugin ne ferme jamais le dialogue qu'un autre vient
 * d'ouvrir en réponse à un clic. L'aveuglement a sa propre clé (skyhand:dialogue_aveugle, enregistrée avec les données du
 * joueur) : un plugin ne retire jamais l'effet pendant le dialogue ouvert par un autre, et un effet resté après un arrêt brutal
 * est retiré à la connexion suivante. Un joueur déjà aveugle pour une autre raison (potion, mob) n'est pas touché.
 */
public final class DialogBlindness implements Listener {

    private static final NamespacedKey KEY = Objects.requireNonNull(NamespacedKey.fromString("skyhand:dialogue_aveugle"));
    private static final NamespacedKey SHOWN = Objects.requireNonNull(NamespacedKey.fromString("skyhand:dialogue_affiche"));
    private static final long GRACE_MS = 400;
    private static final long CHAIN_TICKS = 4;

    /**
     * Clé de clic des boutons qui ferment simplement le dialogue (Fermer, Annuler, C'est parti...), commune à tous les plugins.
     */
    public static final Key CLOSE_KEY = Key.key("skyhand", "dialogue_ferme");

    private final Plugin plugin;
    /** Joueurs aveuglés par ce plugin. */
    private final Set<UUID> tracked = ConcurrentHashMap.newKeySet();
    /** Dialogues affichés par ce plugin et pas encore fermés, avec leur heure d'affichage. */
    private final Map<UUID, Long> open = new ConcurrentHashMap<>();
    private final Map<UUID, Long> clicks = new ConcurrentHashMap<>();
    /** Clic dont la réponse arrive plus tard ({@link #awaitReply}) : le dialogue reste affiché en attendant. */
    private final Set<UUID> awaiting = ConcurrentHashMap.newKeySet();
    private volatile boolean enabled;

    public DialogBlindness(Plugin plugin, boolean enabled) {
        this.plugin = plugin;
        this.enabled = enabled;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Active ou coupe l'aveuglement (rechargement de la configuration). Coupé, les joueurs aveuglés par ce plugin retrouvent la vue.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            sync(this::clearAll);
        }
    }

    /**
     * Action à donner à un bouton qui ferme simplement le dialogue, et au bouton du bas (exit_action, utilisé aussi par Échap) :
     * le serveur est prévenu, ferme le dialogue et rend la vue tout de suite.
     */
    public static DialogAction closeAction() {
        return DialogAction.customClick(CLOSE_KEY, null);
    }

    /**
     * Base d'un dialogue dont un bouton peut rouvrir un dialogue : le client le garde à l'écran après le clic, le suivant le
     * remplace sans clignotement, et cette classe le ferme si rien ne suit. Seulement pour un dialogue affiché par {@link #show}
     * dont tous les boutons sont des actions serveur (custom) : un bouton de lien ou de commande le laisserait ouvert.
     */
    public static DialogBase.Builder stayOpen(DialogBase.Builder base) {
        // un dialogue qui met le jeu en pause doit se fermer au clic : pause coupée (elle n'agit qu'en solo)
        return base.pause(false).afterAction(DialogBase.DialogAfterAction.NONE);
    }

    /**
     * Affiche le dialogue et aveugle le joueur.
     */
    public void show(Player player, Dialog dialog) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        open.put(id, now);
        awaiting.remove(id);
        mark(player, now);
        try {
            player.showDialog(dialog);
        } catch (RuntimeException e) {
            open.remove(id);
            sync(() -> clear(player));
            throw e;
        }
    }

    /**
     * Ferme le dialogue affiché et rend la vue au joueur.
     */
    public void close(Player player) {
        open.remove(player.getUniqueId());
        awaiting.remove(player.getUniqueId());
        player.closeDialog();
        sync(() -> clear(player));
    }

    /**
     * À appeler pendant le traitement d'un clic dont la réponse (nouveau dialogue ou {@link #close}) arrive après plusieurs
     * ticks : le dialogue gardé ouvert ({@link #stayOpen}) n'est pas fermé en attendant.
     */
    public void awaitReply(Player player) {
        if (open.containsKey(player.getUniqueId())) {
            awaiting.add(player.getUniqueId());
        }
    }

    /**
     * À appeler dans onDisable : rend la vue aux joueurs aveuglés par ce plugin.
     */
    public void shutdown() {
        HandlerList.unregisterAll(this);
        clearAll();
        open.clear();
        awaiting.clear();
    }

    /**
     * Note l'heure du dialogue sur le joueur (commune à tous les plugins) et l'aveugle.
     */
    private void mark(Player player, long now) {
        sync(() -> {
            if (!player.isOnline()) {
                return;
            }
            PersistentDataContainer data = player.getPersistentDataContainer();
            data.set(SHOWN, PersistentDataType.LONG, now);
            if (!enabled) {
                return;
            }
            boolean ours = data.has(KEY, PersistentDataType.LONG);
            boolean blind = player.hasPotionEffect(PotionEffectType.BLINDNESS);
            if (blind && !ours) {
                return;
            }
            data.set(KEY, PersistentDataType.LONG, now);
            tracked.add(player.getUniqueId());
            if (!blind) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, PotionEffect.INFINITE_DURATION, 0, false, false, false));
            }
        });
    }

    /**
     * Le dialogue n'est plus à l'écran : plus rien à suivre, vue rendue.
     */
    private void closed(Player player) {
        open.remove(player.getUniqueId());
        awaiting.remove(player.getUniqueId());
        clicks.remove(player.getUniqueId());
        clear(player);
    }

    private void clear(Player player) {
        tracked.remove(player.getUniqueId());
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (data.has(KEY, PersistentDataType.LONG)) {
            data.remove(KEY);
            player.removePotionEffect(PotionEffectType.BLINDNESS);
        }
    }

    private void clearAll() {
        for (UUID id : Set.copyOf(tracked)) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                clear(player);
            }
        }
        tracked.clear();
    }

    /**
     * Le joueur a bougé : le dialogue est fermé, sauf si l'ouverture est trop récente pour que le mouvement en tienne compte.
     */
    private void moved(Player player) {
        Long shownAt = open.get(player.getUniqueId());
        if (shownAt != null && System.currentTimeMillis() - shownAt >= GRACE_MS + 2L * Math.max(0, player.getPing())) {
            closed(player);
        }
    }

    private void sync(Runnable task) {
        if (plugin.getServer().isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    private static Player clicker(PlayerCustomClickEvent event) {
        return event.getCommonConnection() instanceof PlayerGameConnection connection ? connection.getPlayer() : null;
    }

    /**
     * Heure du clic, notée avant que les plugins ne le traitent : un dialogue qu'ils ouvrent en réponse est donc plus récent.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClickStart(PlayerCustomClickEvent event) {
        Player player = clicker(event);
        if (player != null && open.containsKey(player.getUniqueId())) {
            clicks.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    /**
     * Un bouton à action serveur a été cliqué. Bouton de fermeture : le dialogue est fermé tout de suite. Autre bouton : s'il
     * n'a ouvert aucun dialogue (de ce plugin ou d'un autre) dans les 4 ticks et n'attend pas de réponse, le dialogue est fermé
     * (le client l'a déjà fait, sauf pour un dialogue gardé ouvert) et la vue rendue.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(PlayerCustomClickEvent event) {
        Player player = clicker(event);
        Long clickAt = player == null ? null : clicks.remove(player.getUniqueId());
        if (clickAt == null || !plugin.isEnabled()) {
            return;
        }
        if (CLOSE_KEY.equals(event.getIdentifier())) {
            sync(() -> {
                player.closeDialog();
                closed(player);
            });
            return;
        }
        UUID id = player.getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Long mine = open.get(id);
            if (mine == null || !player.isOnline() || mine >= clickAt || awaiting.contains(id)) {
                return;
            }
            Long shown = player.getPersistentDataContainer().get(SHOWN, PersistentDataType.LONG);
            if (shown != null && shown >= clickAt) {
                // un autre plugin a ouvert le dialogue suivant : il le suit lui-même
                open.remove(id);
                return;
            }
            player.closeDialog();
            closed(player);
        }, CHAIN_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        if (open.isEmpty()) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch()) {
            moved(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInput(PlayerInputEvent event) {
        if (open.isEmpty()) {
            return;
        }
        Input input = event.getInput();
        if (input.isForward() || input.isBackward() || input.isLeft() || input.isRight() || input.isJump() || input.isSneak()
                || input.isSprint()) {
            moved(event.getPlayer());
        }
    }

    /**
     * Un inventaire ouvert remplace le dialogue à l'écran.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player
                && (open.containsKey(player.getUniqueId()) || tracked.contains(player.getUniqueId()))) {
            closed(player);
        }
    }

    /**
     * L'effet ne doit pas être enregistré avec les données du joueur.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        closed(event.getPlayer());
    }

    /**
     * Effet resté après un arrêt brutal du serveur.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        clear(event.getPlayer());
    }
}
