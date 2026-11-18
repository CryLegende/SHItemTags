package org.Cry_Legende.sHItemTags;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilisation d'un Nametag, en deux écrans :
 * <ol>
 *     <li>clic droit avec le tag : un menu s'ouvre, le joueur pose l'item à renommer dans la case du centre ; la vitre
 *     devient verte (renommable) ou rouge (item interdit), un clic sur la vitre verte passe au dialogue ;</li>
 *     <li>un dialogue demande le nom. « Aperçu » montre l'item renommé et dit si le nom sera refusé, « Renommer » applique
 *     (ou rouvre le dialogue avec la raison du refus), « Annuler » ferme.</li>
 * </ol>
 * Le tag n'est retiré qu'au moment où le renommage réussit : rien à rendre en cas d'annulation, de déconnexion ou de
 * rechargement. Un client sans dialogues (avant 1.21.6, via ViaVersion) écrit le nom dans le chat après avoir choisi l'item.
 */
final class RenameFlow implements Listener {

    private static final String INPUT = "nom";
    private static final int WIDTH = 260;
    private static final int BUTTON_WIDTH = 125;
    private static final Pattern HEX_LEGACY = Pattern.compile("§x((?:§[0-9a-fA-F]){6})");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /** Menu de pose (3 lignes) : l'item se pose au centre, la vitre à droite valide. */
    private static final int SIZE = 27;
    private static final int ITEM_SLOT = 13;
    private static final int GLASS_SLOT = 15;
    private static final int INFO_SLOT = 11;

    /** Item choisi : sa case et une copie, pour vérifier qu'il n'a pas changé au moment de valider. */
    private record Target(int slot, ItemStack snapshot) {
    }

    /** Résultat du contrôle d'un nom : name est le nom coloré, reason la clé du message de refus (null si accepté). */
    private record Verdict(String name, String reason, int length, String word) {
        Verdict(String name, String reason, int length) {
            this(name, reason, length, "");
        }

        boolean ok() {
            return reason == null;
        }
    }

    /** Menu de pose, reconnu à son propriétaire (holder). */
    private static final class Picker implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final SHItemTags plugin;
    private final DialogBlindness blindness;
    /** Clients sans dialogues : joueurs qui doivent écrire le nom dans le chat (le chat est asynchrone). */
    private final Map<UUID, Target> chatTargets = new ConcurrentHashMap<>();

    RenameFlow(SHItemTags plugin, DialogBlindness blindness) {
        this.plugin = plugin;
        this.blindness = blindness;
    }

    /** Arrêt ou rechargement : l'item posé dans un menu ouvert est rendu avant de fermer le menu. */
    void shutdown() {
        chatTargets.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Picker picker) {
                giveBack(player, picker.getInventory());
                player.closeInventory();
            }
        }
    }

    // ------------------------------------------------------------------ 1. clic droit : menu de pose

    @EventHandler(priority = EventPriority.HIGH)
    public void onUseTag(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
            || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)
            || !plugin.isTag(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (chatTargets.containsKey(player.getUniqueId())) {
            plugin.msg(player, "deja-en-cours", "%cancel_word%", plugin.settings().cancelWord());
            return;
        }
        openPicker(player);
    }

    private void openPicker(Player player) {
        Picker holder = new Picker();
        Inventory menu = Bukkit.createInventory(holder, SIZE, component(plugin.raw("menu.titre")));
        holder.inventory = menu;
        ItemStack filler = button(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < SIZE; i++) {
            if (i != ITEM_SLOT) {
                menu.setItem(i, filler);
            }
        }
        menu.setItem(INFO_SLOT, button(Material.NAME_TAG, plugin.raw("menu.info-nom"), plugin.rawList("menu.info-lore")));
        refreshGlass(menu);
        player.openInventory(menu);
    }

    private static ItemStack button(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(component(name));
        meta.lore(lore.stream().map(RenameFlow::component).toList());
        meta.setHideTooltip(name.isBlank());
        item.setItemMeta(meta);
        return item;
    }

    /** Vitre de validation : blanche sans item, verte si l'item posé peut être renommé, rouge sinon. */
    private void refreshGlass(Inventory menu) {
        ItemStack item = menu.getItem(ITEM_SLOT);
        String state = item == null || item.getType().isAir() ? "vitre-vide" : canRename(item) ? "vitre-valider" : "vitre-refus";
        Material material = switch (state) {
            case "vitre-vide" -> Material.WHITE_STAINED_GLASS_PANE;
            case "vitre-valider" -> Material.LIME_STAINED_GLASS_PANE;
            default -> Material.RED_STAINED_GLASS_PANE;
        };
        menu.setItem(GLASS_SLOT, button(material, plugin.raw("menu." + state + ".nom"), plugin.rawList("menu." + state + ".lore")));
    }

    @EventHandler
    public void onPickerClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Picker picker) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory menu = picker.getInventory();
        int raw = event.getRawSlot();
        boolean top = raw >= 0 && raw < SIZE;
        if (top && raw == GLASS_SLOT) {
            event.setCancelled(true);
            validate(player, menu);
            return;
        }
        // Seule la case centrale bouge dans le menu ; dans l'inventaire du joueur tout est permis, sauf le double-clic
        // qui ramasserait les vitres
        if ((top && raw != ITEM_SLOT) || event.getClick() == ClickType.DOUBLE_CLICK
            || event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> refreshGlass(menu));
    }

    @EventHandler
    public void onPickerDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Picker picker)) {
            return;
        }
        for (int raw : event.getRawSlots()) {
            if (raw < SIZE && raw != ITEM_SLOT) {
                event.setCancelled(true);
                return;
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> refreshGlass(picker.getInventory()));
    }

    @EventHandler
    public void onPickerClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Picker picker && event.getPlayer() instanceof Player player) {
            giveBack(player, picker.getInventory());
        }
    }

    /** Rend l'item posé au centre (fermeture, déconnexion, arrêt). */
    private void giveBack(Player player, Inventory menu) {
        ItemStack item = menu.getItem(ITEM_SLOT);
        menu.setItem(ITEM_SLOT, null);
        if (item != null && !item.getType().isAir()) {
            plugin.give(player, item);
        }
    }

    /**
     * Vitre cliquée : l'item retourne dans une case vide de l'inventaire (jamais fusionné avec une autre pile, qui
     * serait renommée avec lui), puis le dialogue s'ouvre sur cette case.
     */
    private void validate(Player player, Inventory menu) {
        ItemStack item = menu.getItem(ITEM_SLOT);
        if (item == null || item.getType().isAir()) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        if (!canRename(item)) {
            player.sendActionBar(component(plugin.raw("menu.vitre-refus.nom")));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        PlayerInventory inv = player.getInventory();
        int slot = -1;
        for (int i = 0; i < 36 && slot < 0; i++) {
            ItemStack content = inv.getItem(i);
            if (content == null || content.getType().isAir()) {
                slot = i;
            }
        }
        if (slot < 0) {
            player.sendActionBar(component(plugin.raw("menu.inventaire-plein")));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        menu.setItem(ITEM_SLOT, null);
        inv.setItem(slot, item);
        Target target = new Target(slot, item.clone());
        player.closeInventory();
        // Au tick suivant : le menu doit être fermé avant d'ouvrir le dialogue
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (!supportsDialogs(player) || !showDialog(player, target, currentName(item), null)) {
                startChat(player, target);
            }
        });
    }

    // ------------------------------------------------------------------ 2. dialogue : nom, aperçu, validation

    private boolean supportsDialogs(Player player) {
        SHItemTags.Settings settings = plugin.settings();
        if (!settings.dialogs()) {
            return false;
        }
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("ViaVersion")) {
                return true;
            }
            int protocol = ViaSupport.protocol(player);
            return protocol <= 0 || protocol >= settings.minProtocol();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * Affiche le dialogue du nom. typed : texte du champ ; verdict : résultat d'un « Aperçu » ou d'un refus (null au premier
     * affichage). Faux si le dialogue n'a pas pu être construit : l'appelant passe alors par le chat.
     */
    private boolean showDialog(Player player, Target target, String typed, Verdict verdict) {
        try {
            SHItemTags.Settings settings = plugin.settings();
            UUID uuid = player.getUniqueId();
            ClickCallback.Options once = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build();

            List<DialogBody> body = new ArrayList<>();
            // Titre vide : l'icône d'avertissement reste seule sur sa ligne, le titre est en tête du corps
            for (String line : plugin.rawList("dialogue.titre")) {
                body.add(DialogBody.plainMessage(component(line), WIDTH));
            }
            ItemStack preview = target.snapshot().clone();
            Component shownName;
            if (verdict != null && !verdict.name().isEmpty()) {
                ItemMeta meta = preview.getItemMeta();
                meta.setDisplayName(verdict.name());
                preview.setItemMeta(meta);
                shownName = component(verdict.name());
            } else {
                shownName = preview.effectiveName();
            }
            // nom au-dessus, item seul dessous : une description d'item s'affiche à côté de l'icône, donc décentrée
            body.add(DialogBody.plainMessage(shownName, WIDTH));
            body.add(DialogBody.item(preview).showTooltip(true).build());
            String status;
            if (verdict == null) {
                status = plugin.raw("dialogue.aide");
            } else if (verdict.ok()) {
                status = plugin.raw("dialogue.accepte");
            } else {
                status = plugin.raw("dialogue." + verdict.reason())
                    .replace("%length%", String.valueOf(verdict.length()))
                    .replace("%max%", String.valueOf(settings.maxLength()))
                    .replace("%mot%", verdict.word());
            }
            body.add(DialogBody.plainMessage(component(status), WIDTH));
            for (String line : plugin.rawList("dialogue.couleurs")) {
                body.add(DialogBody.plainMessage(component(line), WIDTH));
            }

            // Ce qu'on tape compte au moins autant que le nom coloré (&a = §a, &#rrggbb = 14) : le champ peut s'arrêter là
            DialogInput input = DialogInput.text(INPUT, component(plugin.raw("dialogue.champ")))
                .width(WIDTH)
                .maxLength(Math.max(1, settings.maxLength()))
                .initial(typed == null ? "" : typed.substring(0, Math.min(typed.length(), Math.max(1, settings.maxLength()))))
                .build();
            ActionButton rename = ActionButton.builder(component(plugin.raw("dialogue.bouton-renommer")))
                .tooltip(component(plugin.raw("dialogue.bouton-renommer-info")))
                .width(BUTTON_WIDTH)
                .action(DialogAction.customClick((response, audience) ->
                    onMainThread(uuid, online -> confirm(online, target, response.getText(INPUT))), once))
                .build();
            ActionButton preview2 = ActionButton.builder(component(plugin.raw("dialogue.bouton-apercu")))
                .tooltip(component(plugin.raw("dialogue.bouton-apercu-info")))
                .width(BUTTON_WIDTH)
                .action(DialogAction.customClick((response, audience) ->
                    onMainThread(uuid, online -> preview(online, target, response.getText(INPUT))), once))
                .build();
            // Bouton du bas (et Échap) : ferme, rien n'a été retiré
            ActionButton cancel = ActionButton.builder(component(plugin.raw("dialogue.bouton-annuler")))
                .width(BUTTON_WIDTH)
                .action(DialogBlindness.closeAction())
                .build();
            DialogBase base = DialogBlindness.stayOpen(DialogBase.builder(Component.empty()))
                .canCloseWithEscape(true)
                // gardé ouvert au clic : le dialogue suivant (erreur, écran d'après) le remplace sans clignoter, sinon il est fermé
                .body(body)
                .inputs(List.of(input))
                .build();
            Dialog dialog = Dialog.create(factory -> factory.empty().base(base)
                .type(DialogType.multiAction(List.of(rename, preview2)).columns(2).exitAction(cancel).build()));
            blindness.show(player, dialog);
            return true;
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.WARNING, "Dialogue de renommage impossible pour " + player.getName(), e);
            return false;
        }
    }

    private void preview(Player player, Target target, String typed) {
        ItemStack item = currentItem(player, target);
        Verdict verdict = item == null ? new Verdict("", "disparu", 0) : check(item, typed);
        reopen(player, target, typed, verdict);
    }

    private void confirm(Player player, Target target, String typed) {
        ItemStack item = currentItem(player, target);
        Verdict verdict = item == null ? new Verdict("", "disparu", 0) : check(item, typed);
        if (verdict.ok() && !consumeTag(player)) {
            verdict = new Verdict(verdict.name(), "plus-de-tag", 0);
        }
        if (!verdict.ok()) {
            logRefusal(player, target.snapshot(), verdict);
            reopen(player, target, typed, verdict);
            return;
        }
        blindness.close(player);
        apply(player, item, verdict.name(), "dialogue");
    }

    private void reopen(Player player, Target target, String typed, Verdict verdict) {
        if (!showDialog(player, target, typed, verdict)) {
            blindness.close(player);
            plugin.msg(player, "erreur-dialogue");
        }
    }

    // ------------------------------------------------------------------ clients sans dialogues : nom dans le chat

    private void startChat(Player player, Target target) {
        chatTargets.put(player.getUniqueId(), target);
        plugin.msg(player, "demande");
        plugin.msg(player, "annulation-info", "%cancel_word%", plugin.settings().cancelWord());
    }

    // LOWEST : le nom tapé ne doit jamais partir dans le chat public
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!chatTargets.containsKey(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        // signedMessage() : le texte exactement comme le client l'a envoyé. message() et originalMessage()
        // sont déjà passés par la décoration du chat des autres plugins, qui retire les codes & du texte d'un joueur.
        String text = event.signedMessage().message().trim();
        Bukkit.getScheduler().runTask(plugin, () -> chatAnswer(player, text));
    }

    private void chatAnswer(Player player, String text) {
        Target target = chatTargets.get(player.getUniqueId());
        if (target == null || !player.isOnline()) {
            return;
        }
        if (text.equalsIgnoreCase(plugin.settings().cancelWord())) {
            chatTargets.remove(player.getUniqueId());
            plugin.msg(player, "annule");
            return;
        }
        ItemStack item = currentItem(player, target);
        if (item == null) {
            chatTargets.remove(player.getUniqueId());
            plugin.msg(player, "disparu");
            return;
        }
        // En cas de refus le joueur reste en attente et peut réessayer (ou annuler)
        Verdict verdict = check(item, text);
        if (!verdict.ok()) {
            logRefusal(player, item, verdict);
            plugin.msg(player, verdict.reason(), "%length%", String.valueOf(verdict.length()),
                "%max%", String.valueOf(plugin.settings().maxLength()), "%mot%", verdict.word());
            return;
        }
        chatTargets.remove(player.getUniqueId());
        if (!consumeTag(player)) {
            plugin.msg(player, "plus-de-tag");
            return;
        }
        apply(player, item, verdict.name(), "chat");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        chatTargets.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ règles et application

    private boolean canRename(ItemStack item) {
        return !plugin.isTag(item) && !plugin.settings().bannedItems().contains(item.getType());
    }

    /** L'item choisi, s'il est toujours dans sa case et toujours renommable ; null sinon. */
    private ItemStack currentItem(Player player, Target target) {
        ItemStack item = player.getInventory().getItem(target.slot());
        if (item == null || !item.isSimilar(target.snapshot()) || !canRename(item)) {
            return null;
        }
        return item;
    }

    private Verdict check(ItemStack item, String typed) {
        String text = typed == null ? "" : typed.strip();
        String name = SHItemTags.color(text);
        String plain = ChatColor.stripColor(name).strip();
        if (plain.isEmpty()) {
            return new Verdict("", "vide", 0);
        }
        SHItemTags.Settings settings = plugin.settings();
        String banned = settings.bannedWords().find(plain);
        if (banned != null) {
            return new Verdict(name, "mot-interdit", name.length(), banned);
        }
        // copie d'un texte du serveur ([money], [bank]...) ou d'un objet spécial, montrée ensuite avec [i]
        String imitated = reservedChar(plain, settings.reservedChars());
        if (imitated == null) {
            imitated = settings.imitations().find(plain);
        }
        if (imitated != null) {
            return new Verdict(name, "imitation", name.length(), imitated);
        }
        if (name.length() > settings.maxLength()) {
            return new Verdict(name, "trop-long", name.length());
        }
        return new Verdict(name, null, name.length());
    }

    /** Premier caractère réservé présent dans le nom, null s'il n'y en a pas. */
    private static String reservedChar(String plain, String reserved) {
        if (reserved.isEmpty()) {
            return null;
        }
        for (int i = 0; i < plain.length(); ) {
            int cp = plain.codePointAt(i);
            if (reserved.indexOf(cp) >= 0) {
                return new String(Character.toChars(cp));
            }
            i += Character.charCount(cp);
        }
        return null;
    }

    /** Retire un Nametag de l'inventaire, de préférence celui en main. Faux s'il n'y en a plus. */
    private boolean consumeTag(Player player) {
        PlayerInventory inv = player.getInventory();
        int found = plugin.isTag(inv.getItemInMainHand()) ? inv.getHeldItemSlot() : -1;
        for (int slot = 0; found < 0 && slot < inv.getSize(); slot++) {
            if (plugin.isTag(inv.getItem(slot))) {
                found = slot;
            }
        }
        if (found < 0) {
            return false;
        }
        ItemStack tag = inv.getItem(found);
        if (tag.getAmount() > 1) {
            tag.setAmount(tag.getAmount() - 1);
        } else {
            inv.setItem(found, null);
        }
        return true;
    }

    private void apply(Player player, ItemStack item, String name, String via) {
        ItemMeta meta = item.getItemMeta();
        plugin.log("renommages", "journal.renomme", "%joueur%", player.getName(), "%item%", describe(item),
            "%ancien%", meta.hasDisplayName() ? ChatColor.stripColor(meta.getDisplayName()) : plugin.raw("journal.sans-nom"),
            "%nouveau%", ChatColor.stripColor(name), "%code%", ampCodes(name), "%via%", via);
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        plugin.msg(player, "renomme", "%name%", name);
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.2f);
    }

    /** Refus d'un nom interdit ou d'une imitation au moment de renommer (pas à l'aperçu) : utile à la modération. */
    private void logRefusal(Player player, ItemStack item, Verdict verdict) {
        if (!"mot-interdit".equals(verdict.reason()) && !"imitation".equals(verdict.reason())) {
            return;
        }
        plugin.log("refus", "journal.refuse", "%joueur%", player.getName(), "%item%", describe(item),
            "%raison%", plugin.raw("journal.raisons." + verdict.reason()), "%mot%", verdict.word(),
            "%nom%", ChatColor.stripColor(verdict.name()));
    }

    /** « 1x diamond_sword » */
    private static String describe(ItemStack item) {
        return item.getAmount() + "x " + item.getType().getKey().getKey();
    }

    /** Nom en codes & (§x§r§r§g§g§b§b -> &#rrggbb), pour le champ du dialogue et le journal. */
    private static String ampCodes(String legacy) {
        Matcher m = HEX_LEGACY.matcher(legacy);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("&#" + m.group(1).replace("§", "")));
        }
        m.appendTail(sb);
        return sb.toString().replace('§', '&');
    }

    // ------------------------------------------------------------------ utilitaires

    /** Nom actuel de l'item en codes & (pour le modifier au lieu de tout réécrire), vide sans nom personnalisé. */
    private static String currentName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta == null || !meta.hasDisplayName() ? "" : ampCodes(meta.getDisplayName());
    }

    static Component component(String text) {
        return LEGACY.deserialize(SHItemTags.color(text)).decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private void onMainThread(UUID uuid, Consumer<Player> action) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                action.accept(online);
            }
        });
    }
}
