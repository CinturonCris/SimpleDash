package c.cris.SimpleDash;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class SimpleDashCommand implements CommandExecutor {

    private final SimpleDash plugin;

    public SimpleDashCommand(SimpleDash plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            if (!player.hasPermission("simpledash.reload")) {
                player.sendMessage(plugin.getMessage("no_permission_reload"));
                return false;
            }
        }

        plugin.reloadConfig();
        plugin.saveMessagesConfig();
        sender.sendMessage(plugin.getMessage("config_reloaded"));
        return true;
    }
}
