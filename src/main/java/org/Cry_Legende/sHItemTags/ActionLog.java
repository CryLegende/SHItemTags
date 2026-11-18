package org.Cry_Legende.sHItemTags;

import org.Cry_Legende.sHLogs.api.SHLogsAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Écrit dans SHLogs, ou dans la console sur un serveur sans SHLogs. */
final class ActionLog {

    private ActionLog() {
    }

    static void write(Plugin plugin, String file, String message) {
        if (file == null || file.isEmpty() || message == null || message.isEmpty()) {
            return;
        }
        if (Bukkit.getPluginManager().isPluginEnabled("SHLogs")) {
            SHLogsAPI.log(plugin, file, message);
        } else {
            plugin.getLogger().info(file + " : " + message);
        }
    }
}
