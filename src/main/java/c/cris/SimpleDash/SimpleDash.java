package c.cris.SimpleDash;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class SimpleDash extends JavaPlugin implements Listener {

    private final HashMap<UUID, Long> cooldowns = new HashMap<>();
    private final HashMap<UUID, Long> readyPlayers = new HashMap<>();
    private final HashMap<UUID, Long> lastCooldownMessage = new HashMap<>();
    private boolean itemsAdderEnabled = false;
    private static final long READY_TIMEOUT = 3000;
    private static final long MIN_CLICK_DELAY = 200;

    /*
     * Plugin initialization method
     * Registers event listeners, loads configuration files,
     * and checks for ItemsAdder compatibility
     */
    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getLogger().info("[SimpleDash] Event Listener Registered.");

        saveDefaultConfig();
        saveMessagesConfig();

        getCommand("simpledash").setExecutor(new SimpleDashCommand(this));

        itemsAdderEnabled = Bukkit.getPluginManager().isPluginEnabled("ItemsAdder");
        if (itemsAdderEnabled) {
            Bukkit.getLogger().info(ChatColor.GREEN + "[SimpleDash] ItemsAdder detected, custom item support enabled.");
        } else {
            Bukkit.getLogger().warning(ChatColor.RED + "[SimpleDash] ItemsAdder not found. Custom item functionality will be disabled.");
        }

        Bukkit.getLogger().info(ChatColor.GREEN + "[SimpleDash] Plugin enabled successfully!");
    }

    /*
     * Handles player right-click interactions to trigger the dash mechanic
     * Validates permissions, items, and shield restrictions before proceeding
     */
    @EventHandler
    public void onPlayerDash(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand();

        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        if (!player.hasPermission("simpledash.use")) {
            sendMessage(player, "no_permission");
            return;
        }

        boolean allowOffhandDash = getConfig().getBoolean("dash.allow_offhand_item", false);
        
        if (hand == EquipmentSlot.HAND) {
            if (!hasValidDashItem(player, EquipmentSlot.HAND)) {
                return;
            }
        } else if (hand == EquipmentSlot.OFF_HAND) {
            if (!allowOffhandDash) {
                return;
            }
            if (!hasValidDashItem(player, EquipmentSlot.OFF_HAND)) {
                return;
            }
        } else {
            return;
        }

        boolean allowShield = getConfig().getBoolean("dash.allow_with_shield", false);
        ItemStack oppositeHandItem = (hand == EquipmentSlot.HAND) 
            ? player.getInventory().getItem(EquipmentSlot.OFF_HAND) 
            : player.getInventory().getItem(EquipmentSlot.HAND);
        
        if (!allowShield && oppositeHandItem != null && oppositeHandItem.getType() == Material.SHIELD) {
            return;
        }

        handleDashClick(player, hand);
    }
    
    /*
     * Manages the double-click dash activation system
     * Tracks player clicks, enforces cooldowns, and handles timing windows
     * between first and second clicks
     */
    private void handleDashClick(Player player, EquipmentSlot hand) {
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        
        long cooldownSeconds = getConfig().getLong("dash.cooldown", 0);
        if (cooldownSeconds > 0) {
            long lastUsed = cooldowns.getOrDefault(playerId, 0L);
            if (currentTime - lastUsed < cooldownSeconds * 1000) {
                long remainingTime = (cooldownSeconds * 1000 - (currentTime - lastUsed)) / 1000;
                
                long lastMessage = lastCooldownMessage.getOrDefault(playerId, 0L);
                if (currentTime - lastMessage > 1000) {
                    playSound(player, "cooldown_sound");
                    sendMessage(player, "cooldown_active", "{seconds}", String.valueOf(remainingTime));
                    lastCooldownMessage.put(playerId, currentTime);
                }
                return;
            }
        }
        
        if (readyPlayers.containsKey(playerId)) {
            long readyTime = readyPlayers.get(playerId);
            
            if (currentTime - readyTime < MIN_CLICK_DELAY) {
                return;
            }
            
            if (currentTime - readyTime < READY_TIMEOUT) {
                executeDash(player, cooldownSeconds, hand);
                readyPlayers.remove(playerId);
                return;
            } else {
                readyPlayers.remove(playerId);
            }
        }
        
        readyPlayers.put(playerId, currentTime);
        sendMessage(player, "dash_ready");
        
        final long readyTimeStamp = currentTime;
        new BukkitRunnable() {
            @Override
            public void run() {
                if (readyPlayers.containsKey(playerId)) {
                    long readyTime = readyPlayers.get(playerId);
                    if (readyTimeStamp == readyTime) {
                        readyPlayers.remove(playerId);
                    }
                }
            }
        }.runTaskLater(this, (READY_TIMEOUT / 1000) * 20 + 5);
    }
    
    /*
     * Validates if the player is holding a valid dash item in the specified hand
     * Supports both vanilla Minecraft items and custom ItemsAdder items
     * Also checks for items by display name
     */
    private boolean hasValidDashItem(Player player, EquipmentSlot hand) {
        ItemStack itemInHand = (hand == EquipmentSlot.HAND) 
            ? player.getInventory().getItemInMainHand() 
            : player.getInventory().getItemInOffHand();
            
        if (itemInHand == null || itemInHand.getType() == Material.AIR) {
            return false;
        }
        
        List<String> dashItems = getConfig().getStringList("dash_item");
        List<String> dashItemNames = getConfig().getStringList("dash_item_names");
        
        for (String dashItem : dashItems) {
            if (dashItem.contains(":")) {
                if (itemsAdderEnabled) {
                    try {
                        Class<?> customStackClass = Class.forName("dev.lone.itemsadder.api.CustomStack");
                        Method byItemStackMethod = customStackClass.getMethod("byItemStack", ItemStack.class);
                        Object handStack = byItemStackMethod.invoke(null, itemInHand);
                        if (handStack != null) {
                            Method getIdMethod = customStackClass.getMethod("getId");
                            Object handStackId = getIdMethod.invoke(handStack);
                            if (handStackId.equals(dashItem)) {
                                return true;
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } else {
                if (itemInHand.getType() == Material.valueOf(dashItem.toUpperCase())) {
                    return true;
                }
            }
        }
        
        if (itemInHand.hasItemMeta()) {
            ItemMeta meta = itemInHand.getItemMeta();
            if (meta.hasDisplayName()) {
                String displayName = ChatColor.stripColor(meta.getDisplayName());
                for (String dashItemName : dashItemNames) {
                    String strippedName = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', dashItemName));
                    if (displayName.equalsIgnoreCase(strippedName)) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }
    
    /*
     * Executes the dash movement for the player
     * Applies velocity, plays sounds and effects, then sets cooldown
     */
    private void executeDash(Player player, long cooldownSeconds, EquipmentSlot hand) {
        double forwardPower = getConfig().getDouble("dash.forward_power", 1.5);
        double upwardPower = getConfig().getBoolean("dash.allow_upward", false) ? getConfig().getDouble("dash.upward_power", 0.5) : 0;
        double speedMultiplier = getConfig().getDouble("dash.speed_multiplier", 1.0);

        Vector dashVector = player.getLocation().getDirection().multiply(forwardPower).setY(upwardPower).multiply(speedMultiplier);
        player.setVelocity(dashVector);
        sendMessage(player, "dash_used");
        playSound(player, "dash_sound");

        handleParticleEffects(player, dashVector);

        if (cooldownSeconds > 0) {
            cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
            applyCooldownToItem(player, (int) cooldownSeconds, hand);
        }
    }

    /*
     * Creates a visual particle trail effect following the player during dash
     * Uses a scheduled task to spawn particles at intervals along the dash path
     */
    private void handleParticleEffects(Player player, Vector dashVector) {
        try {
            boolean effectsEnabled = getConfig().getBoolean("effects.enabled", false);
            if (effectsEnabled) {
                String effectName = getConfig().getString("effects.type", "FLAME").toUpperCase();
                Particle effect = Particle.valueOf(effectName);

                int count = getConfig().getInt("effects.count", 10);
                double offset = getConfig().getDouble("effects.offset", 0.5);
                double trailInterval = getConfig().getDouble("effects.trail_interval", 0.2);

                int maxSteps = (int) (dashVector.length() / (trailInterval * 1.5));

                Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
                    private final Vector dashDirection = dashVector.clone().normalize().multiply(trailInterval);
                    private int steps = 0;
                    private final Player taskPlayer = player;

                    @Override
                    public void run() {
                        if (steps >= maxSteps || taskPlayer.isOnGround()) {
                            Bukkit.getScheduler().cancelTask(this.hashCode());
                            return;
                        }

                        taskPlayer.getWorld().spawnParticle(effect, taskPlayer.getLocation(), count, offset, offset, offset);
                        steps++;
                    }
                }, 0L, 1L);
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[SimpleDash] Error generating particles for the dash: " + e.getMessage());
            player.sendMessage(ChatColor.RED + "There was a problem generating the dash effects.");
        }
    }

    /*
     * Plays a configured sound effect for the player
     * Sound type, volume, and pitch are read from configuration
     */
    private void playSound(Player player, String soundConfigKey) {
        if (getConfig().getBoolean(soundConfigKey + ".enabled", false)) {
            Sound sound = Sound.valueOf(getConfig().getString(soundConfigKey + ".type", "ENTITY_ENDER_DRAGON_FLAP").toUpperCase());
            float volume = (float) getConfig().getDouble(soundConfigKey + ".volume", 1.0);
            float pitch = (float) getConfig().getDouble(soundConfigKey + ".pitch", 1.0);
            player.playSound(player.getLocation(), sound, volume, pitch);
        }
    }

    /*
     * Sends a customizable message to the player with placeholder replacement
     * Respects the messages-enable config option and filters out empty messages
     */
    private void sendMessage(Player player, String key, String... replacements) {
        boolean messagesEnabled = getConfig().getBoolean("messages-enable", true);
        if (!messagesEnabled) {
            return;
        }
        
        String message = getMessage(key);
        
        String messageWithoutColors = ChatColor.stripColor(message);
        if (messageWithoutColors == null || messageWithoutColors.trim().isEmpty()) {
            return;
        }
        
        for (int i = 0; i < replacements.length; i += 2) {
            if (i + 1 < replacements.length) {
                message = message.replace(replacements[i], replacements[i + 1]);
            }
        }
        
        player.sendMessage(message);
    }

    /*
     * Applies a visual cooldown indicator to the specific item used for dash
     * This provides clear feedback about when the dash can be used again
     */
    private void applyCooldownToItem(Player player, int seconds, EquipmentSlot hand) {
        ItemStack item = (hand == EquipmentSlot.HAND) 
            ? player.getInventory().getItemInMainHand() 
            : player.getInventory().getItemInOffHand();
            
        if (item != null && item.getType() != Material.AIR) {
            player.setCooldown(item.getType(), seconds * 20);
        }
    }

    /*
     * Retrieves and formats a message from the messages configuration file
     * Translates color codes and provides a fallback if message is not found
     */
    String getMessage(String key) {
        FileConfiguration messages = getMessagesConfig();
        return ChatColor.translateAlternateColorCodes('&', messages.getString(key, "Message not found: " + key));
    }

    /*
     * Loads the messages.yml configuration file
     */
    private FileConfiguration getMessagesConfig() {
        return YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
    }

    /*
     * Saves the default messages.yml file if it doesn't already exist
     */
    void saveMessagesConfig() {
        File file = new File(getDataFolder(), "messages.yml");
        if (!file.exists()) {
            saveResource("messages.yml", false);
        }
    }
}
