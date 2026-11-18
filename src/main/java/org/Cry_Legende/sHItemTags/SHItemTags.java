package org.Cry_Legende.sHItemTags;

import de.tr7zw.nbtapi.NBT;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SHItemTags extends JavaPlugin implements CommandExecutor, TabCompleter {

    // Marqueur NBT d'ItemTags : les anciens tags restent reconnus
    private static final String NBT_KEY = "ItemTagType";
    private static final String NAME_TAG = "ITEM_NAME_TAG";
    private static final String PERM_GIVE = "shitemtags.admin.give";
    private static final String PERM_GIVEALL = "shitemtags.admin.giveall";
    private static final String PERM_RELOAD = "shitemtags.admin.reload";
    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");

    /** Réglages lus une fois par chargement (le clic droit et le contrôle d'un nom ne relisent pas la config). */
    record Settings(String cancelWord, int maxLength, BannedWords bannedWords, BannedWords imitations, String reservedChars,
                    Set<Material> bannedItems,
                    Material tagMaterial, boolean dialogs, int minProtocol) {
    }

    private volatile YamlConfiguration messages = new YamlConfiguration();
    private volatile Settings settings;
    private DialogBlindness blindness;
    private RenameFlow flow;

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
        settings = readSettings(getConfig());
        blindness = new DialogBlindness(this, getConfig().getBoolean("aveugler-pendant-dialogues", true));
        flow = new RenameFlow(this, blindness);
        getServer().getPluginManager().registerEvents(flow, this);
        getCommand("shitemtags").setExecutor(this);
        getCommand("shitemtags").setTabCompleter(this);
    }

    @Override
    public void onDisable() {
        // Le tag n'est retiré qu'à la réussite d'un renommage : rien à rendre aux joueurs en cours
        if (flow != null) {
            flow.shutdown();
        }
        if (blindness != null) {
            blindness.shutdown();
        }
    }

    Settings settings() {
        return settings;
    }

    private Settings readSettings(FileConfiguration config) {
        Set<Material> items = EnumSet.noneOf(Material.class);
        for (String name : config.getStringList("items-interdits")) {
            Material material = Material.matchMaterial(name);
            if (material == null) {
                getLogger().warning("items-interdits : matériau inconnu " + name);
            } else {
                items.add(material);
            }
        }
        BannedWords words = config.isList("mots-interdits")
            // ancien format (simple liste) : chaque mot est cherché partout, comme avant
            ? new BannedWords(List.of(), config.getStringList("mots-interdits"), List.of())
            : new BannedWords(config.getStringList("mots-interdits.mots"), config.getStringList("mots-interdits.morceaux"),
                config.getStringList("mots-interdits.exceptions"));
        Material tag = Material.matchMaterial(config.getString("tag.materiau", "NAME_TAG"));
        return new Settings(
            config.getString("mot-annulation", "annuler"),
            config.getInt("longueur-max", 45),
            words,
            new BannedWords(config.getStringList("imitations.mots"), config.getStringList("imitations.morceaux"),
                config.getStringList("imitations.exceptions")),
            java.util.Objects.requireNonNullElse(config.getString("imitations.caracteres"), ""),
            items,
            tag == null ? Material.NAME_TAG : tag,
            config.getBoolean("dialogues.actif", true),
            config.getInt("dialogues.min-protocol", 771));
    }

    // ------------------------------------------------------------------ tag

    private ItemStack createTag(int amount) {
        ItemStack tag = new ItemStack(settings.tagMaterial(), Math.max(1, amount));
        ItemMeta meta = tag.getItemMeta();
        meta.setDisplayName(color(getConfig().getString("tag.nom", "")));
        meta.setLore(getConfig().getStringList("tag.lore").stream().map(SHItemTags::color).toList());
        tag.setItemMeta(meta);
        NBT.modify(tag, nbt -> {
            nbt.setString(NBT_KEY, NAME_TAG);
        });
        return tag;
    }

    boolean isTag(ItemStack item) {
        // Le matériau d'abord : la lecture NBT n'est faite que pour un Nametag (ceux d'ItemTags sont des NAME_TAG)
        if (item == null || (item.getType() != Material.NAME_TAG && item.getType() != settings.tagMaterial()) || !item.hasItemMeta()) {
            return false;
        }
        return NBT.get(item, nbt -> {
            return NAME_TAG.equals(nbt.getString(NBT_KEY));
        });
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
            log("dons", "journal.donne-a-tous", "%source%", sender.getName(), "%nombre%", String.valueOf(amount),
                "%joueurs%", String.valueOf(Bukkit.getOnlinePlayers().size()));
            msg(sender, "donne-a-tous", "%amount%", String.valueOf(amount));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg(sender, "joueur-hors-ligne", "%value%", args[1]);
            return true;
        }
        give(target, createTag(amount));
        log("dons", "journal.donne", "%source%", sender.getName(), "%nombre%", String.valueOf(amount), "%joueur%", target.getName());
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
     * fichier et la première ligne de l'erreur, null quand tout est rechargé. Un dialogue déjà ouvert garde son item.
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
        settings = readSettings(getConfig());
        blindness.setEnabled(getConfig().getBoolean("aveugler-pendant-dialogues", true));
        return null;
    }

    // ------------------------------------------------------------------ utilitaires

    /**
     * Ligne de journal SHLogs : fichier lu dans config.yml › journal (vide = pas de ligne), texte dans messages.yml › journal,
     * sans codes couleur.
     */
    void log(String fileKey, String messageKey, String... replacements) {
        // un seul argument : lit aussi la valeur du jar quand la section manque (config d'avant le journal)
        String file = getConfig().getString("journal." + fileKey);
        // couleurs retirées du texte avant les variables : un nom en codes & (%code%) reste lisible
        String text = ChatColor.stripColor(color(raw(messageKey)));
        if (file == null || file.isEmpty() || text.isEmpty()) {
            return;
        }
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        ActionLog.write(this, file, text);
    }

    void give(Player player, ItemStack item) {
        for (ItemStack left : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItem(player.getLocation(), left);
        }
    }

    /** Texte brut d'un message (codes & non traduits), vide s'il manque. */
    String raw(String key) {
        // La forme à un argument de getString lit aussi les valeurs par défaut du jar
        String message = messages.getString(key);
        return message == null ? "" : message;
    }

    /** Message sur plusieurs lignes : liste YAML ou texte avec \n. */
    List<String> rawList(String key) {
        if (messages.isList(key)) {
            return messages.getStringList(key);
        }
        String message = raw(key);
        return message.isEmpty() ? List.of() : List.of(message.split("\n"));
    }

    void msg(CommandSender sender, String key, String... replacements) {
        String message = raw(key);
        if (message.isEmpty()) {
            return;
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
