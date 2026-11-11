package org.Cry_Legende.sHItemTags;

import de.tr7zw.nbtapi.NBT;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    private static final String PERM_GIVE = "shitemtags.admin.give";
    private static final String PERM_GIVEALL = "shitemtags.admin.giveall";
    private static final String PERM_RELOAD = "shitemtags.admin.reload";
    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");

    // Joueurs qui ont utilisé un tag et doivent écrire le nom (le chat est asynchrone)
    private final Set<UUID> renaming = ConcurrentHashMap.newKeySet();

    private volatile YamlConfiguration messages = new YamlConfiguration();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateMessages();
        try {
            messages = readMessages();
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().warning("messages.yml illisible, textes par défaut utilisés : " + firstLine(e));
            messages = defaultMessages();
        }
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
        // signedMessage() : le texte exactement comme le client l'a envoyé. message() et originalMessage()
        // sont déjà passés par la décoration du chat des autres plugins, qui retire les codes & du texte d'un joueur.
        String text = event.signedMessage().message().trim();
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

    // ------------------------------------------------------------------ commande (SHCrates : shitemtags give %player_name% itemnametag 1)

    /** Sous-commandes que l'expéditeur a le droit d'utiliser, avec leur forme d'usage. */
    private List<String> allowedSubcommands(CommandSender sender) {
        List<String> allowed = new ArrayList<>();
        if (sender.hasPermission(PERM_GIVE)) {
            allowed.add("give <joueur> itemnametag [nombre]");
        }
        if (sender.hasPermission(PERM_GIVEALL)) {
            allowed.add("giveall itemnametag [nombre]");
        }
        if (sender.hasPermission(PERM_RELOAD)) {
            allowed.add("reload");
        }
        return allowed;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        List<String> allowed = allowedSubcommands(sender);
        if (allowed.isEmpty()) {
            msg(sender, "pas-de-permission");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload") && sender.hasPermission(PERM_RELOAD)) {
            String[] error = reload();
            if (error != null) {
                msg(sender, "reload-failed", "%file%", error[0], "%error%", error[1]);
            } else {
                msg(sender, "recharge");
            }
            return true;
        }
        boolean all = sub.equals("giveall");
        int typeIndex = all ? 1 : 2;
        if ((!all && !sub.equals("give")) || !sender.hasPermission(all ? PERM_GIVEALL : PERM_GIVE) || args.length <= typeIndex) {
            msg(sender, "usage", "%subs%", String.join(" &7|&e /shitemtags ", allowed));
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
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission(PERM_GIVE)) {
                subs.add("give");
            }
            if (sender.hasPermission(PERM_GIVEALL)) {
                subs.add("giveall");
            }
            if (sender.hasPermission(PERM_RELOAD)) {
                subs.add("reload");
            }
            return StringUtil.copyPartialMatches(args[0], subs, new ArrayList<>());
        }
        boolean all = args[0].equalsIgnoreCase("giveall");
        if (!(all ? sender.hasPermission(PERM_GIVEALL) : args[0].equalsIgnoreCase("give") && sender.hasPermission(PERM_GIVE))) {
            return List.of();
        }
        if ((all && args.length == 2) || (!all && args.length == 3)) {
            return StringUtil.copyPartialMatches(args[args.length - 1], List.of("itemnametag"), new ArrayList<>());
        }
        return !all && args.length == 2 ? null : List.of();
    }

    // ------------------------------------------------------------------ configuration et messages

    /**
     * Les textes étaient dans la section messages: de config.yml. Au premier démarrage sans messages.yml, le fichier
     * est créé avec les textes de ce serveur, complétés par les valeurs du jar. La section de config.yml n'est plus
     * lue ensuite. usage change de forme (sous-commandes permises) : il vient toujours du jar.
     */
    private void migrateMessages() {
        File file = new File(getDataFolder(), "messages.yml");
        if (file.exists()) {
            return;
        }
        YamlConfiguration created = defaultMessages();
        ConfigurationSection old = getConfig().getConfigurationSection("messages");
        if (old != null) {
            for (String key : old.getKeys(false)) {
                if (!key.equals("usage") && old.isString(key)) {
                    created.set(key, old.getString(key));
                }
            }
        }
        try {
            getDataFolder().mkdirs();
            created.save(file);
        } catch (IOException e) {
            getLogger().warning("messages.yml non créé : " + e.getMessage());
        }
    }

    private YamlConfiguration defaultMessages() {
        try (InputStream in = getResource("messages.yml")) {
            if (in != null) {
                return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            getLogger().warning("messages.yml du jar illisible : " + e.getMessage());
        }
        return new YamlConfiguration();
    }

    /** messages.yml du serveur ; les clés absentes retombent sur le texte du jar. */
    private YamlConfiguration readMessages() throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new File(getDataFolder(), "messages.yml"));
        yaml.setDefaults(defaultMessages());
        return yaml;
    }

    private static String firstLine(Exception e) {
        String text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        int end = text.indexOf('\n');
        return (end < 0 ? text : text.substring(0, end)).trim();
    }

    /**
     * Relit config.yml et messages.yml. Si l'un des deux a une erreur de syntaxe, rien n'est appliqué : renvoie le
     * fichier et la première ligne de l'erreur, null quand tout est rechargé. Les joueurs qui écrivent un nom ne sont
     * pas touchés.
     */
    private String[] reload() {
        File configFile = new File(getDataFolder(), "config.yml");
        try {
            new YamlConfiguration().load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            return new String[]{configFile.getName(), firstLine(e)};
        }
        YamlConfiguration newMessages;
        try {
            newMessages = readMessages();
        } catch (IOException | InvalidConfigurationException e) {
            return new String[]{"messages.yml", firstLine(e)};
        }
        reloadConfig();
        messages = newMessages;
        return null;
    }

    // ------------------------------------------------------------------ utilitaires

    private void give(Player player, ItemStack item) {
        for (ItemStack left : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItem(player.getLocation(), left);
        }
    }

    private void msg(CommandSender sender, String key, String... replacements) {
        // La forme à un argument de getString lit aussi les valeurs par défaut du jar
        String message = messages.getString(key);
        if (message == null) {
            message = "";
        }
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
