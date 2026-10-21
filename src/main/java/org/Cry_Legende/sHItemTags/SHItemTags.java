package org.Cry_Legende.sHItemTags;

import de.tr7zw.nbtapi.NBT;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SHItemTags extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    // Marqueur NBT d'ItemTags : les anciens tags restent reconnus
    private static final String NBT_KEY = "ItemTagType";
    private static final String NAME_TAG = "ITEM_NAME_TAG";
    private static final String PERMISSION = "itemtags.command.give";
    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");

    // Joueurs qui ont utilisé un tag et doivent écrire le nom (le chat est asynchrone)
    private final Set<UUID> renaming = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("shitemtags").setExecutor(this);
        getCommand("shitemtags").setTabCompleter(this);
    }

    @Override
    public void onDisable() {
        // Rechargement ou arrêt : on rend les tags en cours d'utilisation
        for (UUID uuid : renaming) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                give(player, createTag(1));
            }
        }
        renaming.clear();
    }

    // ------------------------------------------------------------------ tag

    private ItemStack createTag(int amount) {
        Material material = Material.matchMaterial(getConfig().getString("tag.materiau", "NAME_TAG"));
        ItemStack tag = new ItemStack(material == null ? Material.NAME_TAG : material, Math.max(1, amount));
        ItemMeta meta = tag.getItemMeta();
        meta.setDisplayName(color(getConfig().getString("tag.nom", "")));
        meta.setLore(getConfig().getStringList("tag.lore").stream().map(SHItemTags::color).toList());
        tag.setItemMeta(meta);
        NBT.modify(tag, nbt -> {
            nbt.setString(NBT_KEY, NAME_TAG);
        });
        return tag;
    }

    private static boolean isTag(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        return NBT.get(item, nbt -> {
            return NAME_TAG.equals(nbt.getString(NBT_KEY));
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUseTag(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
            || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)
            || !isTag(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (renaming.contains(player.getUniqueId())) {
            msg(player, "deja-en-cours");
            return;
        }
        // Comme ItemTags : le tag est consommé tout de suite, rendu en cas d'annulation
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getAmount() > 1) {
            hand.setAmount(hand.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
        renaming.add(player.getUniqueId());
        msg(player, "demande");
        msg(player, "annulation-info", "%cancel_word%", getConfig().getString("mot-annulation", "annuler"));
    }

    // LOWEST : le nom tapé ne doit jamais partir dans le chat public
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!renaming.contains(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(this, () -> rename(player, text));
    }

    private void rename(Player player, String text) {
        if (!player.isOnline() || !renaming.contains(player.getUniqueId())) {
            return;
        }
        if (text.equalsIgnoreCase(getConfig().getString("mot-annulation", "annuler"))) {
            renaming.remove(player.getUniqueId());
            give(player, createTag(1));
            msg(player, "annule");
            return;
        }
        // En cas d'erreur le joueur reste en attente et peut réessayer (ou annuler)
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            msg(player, "air");
            return;
        }
        if (getConfig().getStringList("items-interdits").stream().anyMatch(m -> m.equalsIgnoreCase(item.getType().name()))) {
            msg(player, "item-interdit");
            return;
        }
        String lower = ChatColor.stripColor(color(text)).toLowerCase(Locale.ROOT);
        if (getConfig().getStringList("mots-interdits").stream().anyMatch(w -> lower.contains(w.toLowerCase(Locale.ROOT)))) {
            msg(player, "mot-interdit");
            return;
        }
        String name = color(text);
        if (name.length() > getConfig().getInt("longueur-max", 45)) {
            msg(player, "trop-long");
            return;
        }
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        renaming.remove(player.getUniqueId());
        msg(player, "renomme", "%name%", name);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (renaming.remove(event.getPlayer().getUniqueId())) {
            give(event.getPlayer(), createTag(1));
        }
    }

    // ------------------------------------------------------------------ commande (SHCrates : itemtags give %player_name% itemnametag 1)

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            msg(sender, "pas-de-permission");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            reloadConfig();
            msg(sender, "recharge");
            return true;
        }
        boolean all = sub.equals("giveall");
        int typeIndex = all ? 1 : 2;
        if ((!all && !sub.equals("give")) || args.length <= typeIndex) {
            msg(sender, "usage");
            return true;
        }
        if (!args[typeIndex].equalsIgnoreCase("itemnametag") && !args[typeIndex].equalsIgnoreCase(NAME_TAG)) {
            msg(sender, "type-invalide");
            return true;
        }
        int amount = 1;
        if (args.length > typeIndex + 1) {
            try {
                amount = Integer.parseInt(args[typeIndex + 1]);
            } catch (NumberFormatException e) {
                msg(sender, "pas-un-nombre", "%value%", args[typeIndex + 1]);
                return true;
            }
        }
        if (all) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                give(player, createTag(amount));
            }
            msg(sender, "donne-a-tous", "%amount%", String.valueOf(amount));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg(sender, "joueur-hors-ligne", "%value%", args[1]);
            return true;
        }
        give(target, createTag(amount));
        if (sender instanceof Player) {
            msg(sender, "donne", "%amount%", String.valueOf(amount), "%player%", target.getName());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], List.of("give", "giveall", "reload"), new ArrayList<>());
        }
        boolean all = args[0].equalsIgnoreCase("giveall");
        if ((all && args.length == 2) || (!all && args.length == 3)) {
            return StringUtil.copyPartialMatches(args[args.length - 1], List.of("itemnametag"), new ArrayList<>());
        }
        return !all && args.length == 2 ? null : List.of();
    }

    // ------------------------------------------------------------------ utilitaires

    private void give(Player player, ItemStack item) {
        for (ItemStack left : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItem(player.getLocation(), left);
        }
    }

    private void msg(CommandSender sender, String key, String... replacements) {
        String message = getConfig().getString("messages." + key, "");
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            message = message.replace(replacements[i], replacements[i + 1]);
        }
        for (String line : message.split("\n")) {
            sender.sendMessage(color(line));
        }
    }

    /**
     * Codes &amp; classiques et couleurs hexadécimales &amp;#rrggbb.
     */
    static String color(String text) {
        Matcher m = HEX.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(net.md_5.bungee.api.ChatColor.of("#" + m.group(1)).toString()));
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }
}
