package com.survivalplus;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public class SurvivalPlus extends JavaPlugin implements Listener {

    private static final String TAG = "survivalplus_display";
    private static final String NETA_PREFIX = "\u00a7cNeta \u00a7r";
    private static final String SB_PREFIX = "sp_";
    private static final String DISCORD_DEFAULT = "https://discord.gg/ccYUmyTPsc";
    private static final Pattern TEAM_NAME = Pattern.compile("[A-Za-z0-9_]{3,12}");
    private static final Pattern HOME_NAME = Pattern.compile("[A-Za-z0-9_-]{1,16}");
    private static final long INVITE_TTL_MS = 60_000L;

    // ---- player state ----
    private final Set<UUID> viewers = new HashSet<>();      // /indicator ON
    private final Set<UUID> nvPlayers = new HashSet<>();    // /nightvision ON
    private final Set<UUID> netas = new HashSet<>();        // Neta rank

    // ---- homes ----
    private final Map<UUID, Map<String, Home>> homes = new HashMap<>();
    private final Map<UUID, Long> lastHome = new HashMap<>();

    // ---- teams ----
    private final Map<String, TeamData> teams = new LinkedHashMap<>(); // key = lowercase name
    private final Map<UUID, String> playerTeam = new HashMap<>();      // player -> team key
    private final Map<UUID, Invite> invites = new HashMap<>();

    // ---- HUD ----
    private final Map<String, Holder> holders = new HashMap<>();

    // ---- settings (config.yml) ----
    private int maxHomes, netaMaxHomes, teamMaxHomes;
    private long cooldownMs;
    private boolean showPlayerHearts, showMobHearts, showHotbar, onlyDamaged;
    private double radius;
    private int period;
    private float viewRange, playerHeartsY, hotbarY, mobHeartsY;

    private File dataFile, playersFile;
    private BukkitTask hudTask, nvTask;

    // =====================================================================
    //  Lifecycle
    // =====================================================================

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.yml");
        playersFile = new File(getDataFolder(), "players.yml");

        reloadSettings();
        loadData();
        loadPlayers();
        cleanupStray();

        getServer().getPluginManager().registerEvents(this, this);
        hudTask = Bukkit.getScheduler().runTaskTimer(this, this::hudTick, 20L, period);
        nvTask = Bukkit.getScheduler().runTaskTimer(this, this::nightVisionTick, 20L, 40L);

        for (Player p : Bukkit.getOnlinePlayers()) refreshPlayer(p);
        getLogger().info("SurvivalPlus enabled.");
    }

    @Override
    public void onDisable() {
        if (hudTask != null) hudTask.cancel();
        if (nvTask != null) nvTask.cancel();
        removeAllHolders();
        cleanupStray();
        saveData();
        savePlayers();
    }

    private void reloadSettings() {
        var c = getConfig();
        maxHomes = c.getInt("max-homes", 3);
        netaMaxHomes = c.getInt("neta-max-homes", 5);
        teamMaxHomes = c.getInt("team-max-homes", 3);
        cooldownMs = c.getLong("home-cooldown-minutes", 10L) * 60_000L;

        showPlayerHearts = c.getBoolean("show-player-hearts", true);
        showMobHearts = c.getBoolean("show-mob-hearts", true);
        showHotbar = c.getBoolean("show-player-hotbar", true);
        onlyDamaged = c.getBoolean("mob-hearts-only-when-damaged", false);
        radius = c.getDouble("radius", 24.0);
        period = Math.max(1, c.getInt("update-ticks", 2));
        viewRange = (float) c.getDouble("view-range", 0.5);
        playerHeartsY = (float) c.getDouble("player-hearts-height", 0.8);
        hotbarY = (float) c.getDouble("player-hotbar-height", 1.15);
        mobHeartsY = (float) c.getDouble("mob-hearts-height", 0.45);
    }

    // =====================================================================
    //  Persistence
    // =====================================================================

    private void loadData() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection hs = y.getConfigurationSection("homes");
        if (hs != null) {
            for (String uid : hs.getKeys(false)) {
                UUID u = parseUuid(uid);
                ConfigurationSection sec = hs.getConfigurationSection(uid);
                if (u == null || sec == null) continue;
                Map<String, Home> m = new LinkedHashMap<>();
                for (String hn : sec.getKeys(false)) {
                    Home h = readHome(sec.getConfigurationSection(hn));
                    if (h != null) m.put(hn, h);
                }
                homes.put(u, m);
            }
        }

        ConfigurationSection lh = y.getConfigurationSection("last-home");
        if (lh != null) {
            for (String uid : lh.getKeys(false)) {
                UUID u = parseUuid(uid);
                if (u != null) lastHome.put(u, lh.getLong(uid));
            }
        }

        ConfigurationSection ts = y.getConfigurationSection("teams");
        if (ts != null) {
            for (String key : ts.getKeys(false)) {
                ConfigurationSection t = ts.getConfigurationSection(key);
                if (t == null) continue;
                TeamData td = new TeamData(t.getString("name", key), t.getString("owner", ""));
                try {
                    td.color = ChatColor.valueOf(t.getString("color", "WHITE"));
                } catch (IllegalArgumentException ex) {
                    td.color = ChatColor.WHITE;
                }
                td.admins.addAll(t.getStringList("admins"));
                td.members.addAll(t.getStringList("members"));
                td.allies.addAll(t.getStringList("allies"));
                ConfigurationSection th = t.getConfigurationSection("homes");
                if (th != null) {
                    for (String hn : th.getKeys(false)) {
                        Home h = readHome(th.getConfigurationSection(hn));
                        if (h != null) td.homes.put(hn, h);
                    }
                }
                List<?> raw = t.getList("chest");
                if (raw != null) {
                    td.chest = new ItemStack[54];
                    for (int i = 0; i < Math.min(raw.size(), 54); i++) {
                        Object o = raw.get(i);
                        if (o instanceof ItemStack is) td.chest[i] = is;
                    }
                }
                teams.put(td.key, td);
                for (String m : td.members) {
                    UUID u = parseUuid(m);
                    if (u != null) playerTeam.put(u, td.key);
                }
            }
        }
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, Home>> e : homes.entrySet()) {
            for (Map.Entry<String, Home> h : e.getValue().entrySet()) {
                writeHome(y, "homes." + e.getKey() + "." + h.getKey(), h.getValue());
            }
        }
        for (Map.Entry<UUID, Long> e : lastHome.entrySet()) {
            y.set("last-home." + e.getKey(), e.getValue());
        }
        for (TeamData t : teams.values()) {
            String base = "teams." + t.key;
            y.set(base + ".name", t.name);
            y.set(base + ".owner", t.owner);
            y.set(base + ".color", t.color.name());
            y.set(base + ".admins", new ArrayList<>(t.admins));
            y.set(base + ".members", new ArrayList<>(t.members));
            y.set(base + ".allies", new ArrayList<>(t.allies));
            for (Map.Entry<String, Home> h : t.homes.entrySet()) {
                writeHome(y, base + ".homes." + h.getKey(), h.getValue());
            }
            ItemStack[] snap = t.snapshot();
            if (snap != null) y.set(base + ".chest", Arrays.asList(snap));
        }
        try {
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("data.yml save nahi hui: " + ex.getMessage());
        }
    }

    private void loadPlayers() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(playersFile);
        readUuids(viewers, y.getStringList("viewers"));
        readUuids(nvPlayers, y.getStringList("nightvision"));
        readUuids(netas, y.getStringList("netas"));
    }

    private void savePlayers() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("viewers", uuidStrings(viewers));
        y.set("nightvision", uuidStrings(nvPlayers));
        y.set("netas", uuidStrings(netas));
        try {
            y.save(playersFile);
        } catch (IOException ex) {
            getLogger().severe("players.yml save nahi hui: " + ex.getMessage());
        }
    }

    private static void readUuids(Set<UUID> target, List<String> raw) {
        for (String s : raw) {
            UUID u = parseUuid(s);
            if (u != null) target.add(u);
        }
    }

    private static List<String> uuidStrings(Set<UUID> set) {
        List<String> out = new ArrayList<>();
        for (UUID u : set) out.add(u.toString());
        return out;
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException | NullPointerException ex) {
            return null;
        }
    }

    private static void writeHome(YamlConfiguration y, String path, Home h) {
        y.set(path + ".world", h.world());
        y.set(path + ".x", h.x());
        y.set(path + ".y", h.y());
        y.set(path + ".z", h.z());
        y.set(path + ".yaw", (double) h.yaw());
        y.set(path + ".pitch", (double) h.pitch());
    }

    private static Home readHome(ConfigurationSection s) {
        if (s == null) return null;
        String world = s.getString("world");
        if (world == null) return null;
        return new Home(world, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    // =====================================================================
    //  Commands
    // =====================================================================

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);

        if (name.equals("neta")) { handleNeta(sender, args); return true; }
        if (name.equals("discordlink")) { handleDiscordLink(sender, args); return true; }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Ye command sirf players ke liye hai.");
            return true;
        }

        switch (name) {
            case "sethome" -> setHome(p, args);
            case "home" -> goHome(p, args);
            case "delhome" -> delHome(p, args);
            case "homes" -> listHomes(p);
            case "indicator" -> cmdIndicator(p, args);
            case "nightvision" -> cmdNightVision(p, args);
            case "ec" -> cmdEc(p);
            case "createteam" -> createTeam(p, args);
            case "team" -> handleTeam(p, args);
            default -> { return false; }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (name.equals("team")) {
            if (args.length == 1) {
                for (String s : List.of("help", "info", "invite", "accept", "leave", "kick", "disband",
                        "color", "admin", "enderchest", "sethome", "delhome", "ally")) {
                    if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
                }
            } else if (args.length == 2) {
                String sub = args[0].toLowerCase(Locale.ROOT);
                if (sub.equals("color")) {
                    for (ChatColor c : ChatColor.values()) if (c.isColor()) out.add(c.name().toLowerCase(Locale.ROOT));
                } else if (sub.equals("invite") || sub.equals("kick") || sub.equals("admin")) {
                    for (Player pl : Bukkit.getOnlinePlayers()) out.add(pl.getName());
                }
            }
        } else if (name.equals("home") || name.equals("delhome")) {
            if (sender instanceof Player p && args.length == 1) {
                out.addAll(homes.getOrDefault(p.getUniqueId(), Map.of()).keySet());
            }
        } else if (name.equals("neta") && args.length == 1) {
            for (Player pl : Bukkit.getOnlinePlayers()) out.add(pl.getName());
        }
        return out;
    }

    // ---------------- Homes ----------------

    private int maxHomesFor(Player p) {
        return netas.contains(p.getUniqueId()) ? netaMaxHomes : maxHomes;
    }

    private void setHome(Player p, String[] args) {
        String name = args.length > 0 ? args[0] : "home";
        if (!HOME_NAME.matcher(name).matches()) {
            p.sendMessage("\u00a7c[Home] Home name sirf letters, numbers, _ ya - (max 16) ho sakta hai.");
            return;
        }
        Map<String, Home> m = homes.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashMap<>());
        boolean bypass = p.hasPermission("survivalplus.bypass");
        int limit = bypass ? Integer.MAX_VALUE : maxHomesFor(p);
        if (!m.containsKey(name) && m.size() >= limit) {
            p.sendMessage("\u00a7c[Home] Tum sirf " + limit + " homes set kar sakte ho! Pehle /delhome <name> se ek delete karo.");
            return;
        }
        m.put(name, Home.of(p.getLocation()));
        saveData();
        p.sendMessage("\u00a7e[Home] Home '" + name + "' set ho gaya! (" + m.size() + "/" + (bypass ? "unlimited" : String.valueOf(limit)) + ")");
    }

    private void goHome(Player p, String[] args) {
        UUID id = p.getUniqueId();
        Map<String, Home> mine = homes.getOrDefault(id, Map.of());
        TeamData team = teamOf(id);

        if (args.length == 0) {
            listHomes(p);
            return;
        }
        String name = args[0];
        Home h = mine.get(name);
        if (h == null && team != null) h = team.homes.get(name);
        if (h == null) {
            p.sendMessage("\u00a7c[Home] Home '" + name + "' nahi mila. Homes: " + allHomeNames(p));
            return;
        }

        boolean bypass = p.hasPermission("survivalplus.bypass");
        long now = System.currentTimeMillis();
        if (!bypass) {
            Long last = lastHome.get(id);
            if (last != null && now - last < cooldownMs) {
                p.sendMessage("\u00a7c[Home] Abhi /home use nahi kar sakte. " + formatDuration(cooldownMs - (now - last)) + " baad try karo.");
                return;
            }
        }

        World w = Bukkit.getWorld(h.world());
        if (w == null) {
            p.sendMessage("\u00a7c[Home] Us home ki world load nahi hai.");
            return;
        }
        p.teleport(h.toLocation(w));
        if (!bypass) {
            lastHome.put(id, now);
            saveData();
        }
        p.sendMessage("\u00a7a[Home] Home '" + name + "' pe teleport ho gaye!");
    }

    private void delHome(Player p, String[] args) {
        if (args.length == 0) {
            p.sendMessage("\u00a7c[Home] Use: /delhome <name>");
            return;
        }
        Map<String, Home> m = homes.get(p.getUniqueId());
        if (m == null || m.remove(args[0]) == null) {
            p.sendMessage("\u00a7c[Home] Home '" + args[0] + "' nahi mila.");
            return;
        }
        saveData();
        p.sendMessage("\u00a7e[Home] Home '" + args[0] + "' delete ho gaya.");
    }

    private void listHomes(Player p) {
        UUID id = p.getUniqueId();
        boolean bypass = p.hasPermission("survivalplus.bypass");
        Map<String, Home> mine = homes.getOrDefault(id, Map.of());
        TeamData team = teamOf(id);

        p.sendMessage("\u00a76[Homes] \u00a7ePersonal (" + mine.size() + "/" + (bypass ? "unlimited" : String.valueOf(maxHomesFor(p))) + "): \u00a7f"
                + (mine.isEmpty() ? "koi nahi" : String.join(", ", mine.keySet())));
        if (team != null) {
            p.sendMessage("\u00a76[Homes] \u00a7eTeam " + team.color + team.name + "\u00a7e (" + team.homes.size() + "/" + teamMaxHomes + "): \u00a7f"
                    + (team.homes.isEmpty() ? "koi nahi" : String.join(", ", team.homes.keySet())));
        }
        if (bypass) {
            p.sendMessage("\u00a7a[Home] Aapke paas bypass hai, /home ka cooldown nahi lagta.");
        } else {
            Long last = lastHome.get(id);
            long now = System.currentTimeMillis();
            if (last == null || now - last >= cooldownMs) {
                p.sendMessage("\u00a7a[Home] /home ab use kar sakte ho.");
            } else {
                p.sendMessage("\u00a7c[Home] Agla /home: " + formatDuration(cooldownMs - (now - last)) + " baad.");
            }
        }
    }

    private String allHomeNames(Player p) {
        List<String> names = new ArrayList<>(homes.getOrDefault(p.getUniqueId(), Map.of()).keySet());
        TeamData t = teamOf(p.getUniqueId());
        if (t != null) names.addAll(t.homes.keySet());
        return names.isEmpty() ? "koi nahi" : String.join(", ", names);
    }

    private static String formatDuration(long ms) {
        long totalSec = Math.max(0, (ms + 999) / 1000);
        long min = totalSec / 60;
        long sec = totalSec % 60;
        return min + " min " + sec + " sec";
    }

    // ---------------- Indicator / NightVision / EC ----------------

    private void cmdIndicator(Player p, String[] args) {
        Boolean on = parseSwitch(args);
        if (on == null) {
            p.sendMessage("\u00a7c[Indicator] Use: /indicator true  ya  /indicator false");
            return;
        }
        if (on) {
            viewers.add(p.getUniqueId());
            p.sendMessage("\u00a7a[Indicator] ON \u2192 \u00a77Ab tumhe hearts aur hotbar dikhenge.");
        } else {
            viewers.remove(p.getUniqueId());
            p.sendMessage("\u00a7c[Indicator] OFF \u2192 \u00a77Ab kuch nahi dikhega.");
        }
        savePlayers();
    }

    private void cmdNightVision(Player p, String[] args) {
        Boolean on = parseSwitch(args);
        if (on == null) {
            p.sendMessage("§c[NightVision] Use: /nightvision true  ya  /nightvision false");
            return;
        }
        if (on) {
            nvPlayers.add(p.getUniqueId());
            applyNightVision(p);
            p.sendMessage("§a[NightVision] ON → §7Ab hamesha full brightness rahegi.");
        } else {
            nvPlayers.remove(p.getUniqueId());
            p.removePotionEffect(PotionEffectType.NIGHT_VISION);
            p.sendMessage("§c[NightVision] OFF");
        }
        savePlayers();
    }

    private void applyNightVision(Player p) {
        PotionEffect cur = p.getPotionEffect(PotionEffectType.NIGHT_VISION);
        if (cur != null && cur.isInfinite()) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false, false));
    }

    private void nightVisionTick() {
        for (UUID id : nvPlayers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) applyNightVision(p);
        }
    }

    private void cmdEc(Player p) {
        if (!(netas.contains(p.getUniqueId()) || p.isOp())) {
            p.sendMessage("§c[EC] Ye command sirf Neta rank ya OP ke liye hai.");
            return;
        }
        p.openInventory(p.getEnderChest());
    }

    private static Boolean parseSwitch(String[] a) {
        if (a.length == 0) return null;
        String s = a[0].toLowerCase(Locale.ROOT);
        if (s.equals("true") || s.equals("on") || s.equals("yes")) return Boolean.TRUE;
        if (s.equals("false") || s.equals("off") || s.equals("no")) return Boolean.FALSE;
        return null;
    }

    // ---------------- Neta / Discord ----------------

    private void handleNeta(CommandSender sender, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage("§c[Neta] Ye command sirf OP use kar sakta hai.");
            return;
        }
        if (args.length == 0) {
            sender.sendMessage("§c[Neta] Use: /neta <player>  (dobara likhne par rank hat jayega)");
            return;
        }
        org.bukkit.OfflinePlayer target = Bukkit.getPlayerExact(args[0]);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[0]);
        if (target == null) {
            sender.sendMessage("§c[Neta] Player '" + args[0] + "' nahi mila (wo ek baar server pe aaya hona chahiye).");
            return;
        }
        UUID id = target.getUniqueId();
        String shownName = target.getName() != null ? target.getName() : args[0];
        if (netas.add(id)) {
            sender.sendMessage("§a[Neta] " + shownName + " ko §cNeta §arank mil gaya!");
        } else {
            netas.remove(id);
            sender.sendMessage("§e[Neta] " + shownName + " ka Neta rank hata diya.");
        }
        savePlayers();
        Player online = Bukkit.getPlayer(id);
        if (online != null) refreshPlayer(online);
    }

    private String discordLink() {
        return getConfig().getString("discord-link", DISCORD_DEFAULT);
    }

    private void handleDiscordLink(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§9[Discord] §bHamara Discord: §f" + discordLink());
            return;
        }
        if (!sender.isOp()) {
            sender.sendMessage("§c[Discord] Link sirf OP change kar sakta hai.");
            return;
        }
        String link = args[args.length - 1];
        if (!(link.startsWith("http://") || link.startsWith("https://"))) {
            sender.sendMessage("§c[Discord] Use: /discordlink https://discord.gg/xxxx");
            return;
        }
        getConfig().set("discord-link", link);
        saveConfig();
        sender.sendMessage("§a[Discord] Link set ho gaya: §f" + link);
    }

    private long lastDiscordMsg = 0L;

    // ---------------- Teams ----------------

    private TeamData teamOf(UUID id) {
        String k = playerTeam.get(id);
        return k == null ? null : teams.get(k);
    }

    private static boolean isOwner(TeamData t, UUID id) {
        return t.owner.equals(id.toString());
    }

    private static String nameOf(String uuidStr) {
        UUID u = parseUuid(uuidStr);
        if (u == null) return uuidStr;
        String n = Bukkit.getOfflinePlayer(u).getName();
        return n != null ? n : uuidStr.substring(0, Math.min(8, uuidStr.length()));
    }

    private static String findMember(TeamData t, String name) {
        for (String m : t.members) {
            if (nameOf(m).equalsIgnoreCase(name)) return m;
        }
        return null;
    }

    private void notifyTeam(TeamData t, String msg) {
        for (String m : t.members) {
            UUID u = parseUuid(m);
            Player pl = u == null ? null : Bukkit.getPlayer(u);
            if (pl != null) pl.sendMessage(msg);
        }
    }

    private void refreshTeam(TeamData t) {
        for (String m : t.members) {
            UUID u = parseUuid(m);
            Player pl = u == null ? null : Bukkit.getPlayer(u);
            if (pl != null) refreshPlayer(pl);
        }
    }

    private void createTeam(Player p, String[] args) {
        UUID id = p.getUniqueId();
        if (args.length == 0) {
            p.sendMessage("§c[Team] Use: /createteam <name>");
            return;
        }
        if (teamOf(id) != null) {
            p.sendMessage("§c[Team] Tum pehle se ek team me ho. Pehle /team leave karo.");
            return;
        }
        String name = args[0];
        if (!TEAM_NAME.matcher(name).matches()) {
            p.sendMessage("§c[Team] Team name 3-12 characters ka hona chahiye (letters, numbers, _).");
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (teams.containsKey(key)) {
            p.sendMessage("§c[Team] Is naam ki team pehle se hai.");
            return;
        }
        TeamData t = new TeamData(name, id.toString());
        teams.put(key, t);
        playerTeam.put(id, key);
        saveData();
        refreshPlayer(p);
        p.sendMessage("§a[Team] Team '" + name + "' ban gayi! Tum owner ho. /team help dekho.");
    }

    private void teamHelp(Player p) {
        p.sendMessage("§6[Team] §eCommands:");
        p.sendMessage("§f/createteam <n> §7- team banao");
        p.sendMessage("§f/team info [name] §7- team ki info");
        p.sendMessage("§f/team invite <player> §7- invite bhejo (owner/admin)");
        p.sendMessage("§f/team accept §7- invite accept karo");
        p.sendMessage("§f/team leave §7- team chhodo");
        p.sendMessage("§f/team kick <player> §7- member nikalo (owner/admin)");
        p.sendMessage("§f/team admin <player> §7- admin do/hatao (owner)");
        p.sendMessage("§f/team color <color> §7- team ka color (owner/admin)");
        p.sendMessage("§f/team enderchest §7- shared team chest");
        p.sendMessage("§f/team sethome <name> / delhome <name> §7- team homes (owner/admin)");
        p.sendMessage("§f/team ally <team> §7- ally add/remove (owner/admin)");
        p.sendMessage("§f/team disband §7- team delete karo (owner)");
    }

    private void handleTeam(Player p, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            teamHelp(p);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        UUID id = p.getUniqueId();

        if (sub.equals("accept")) {
            teamAccept(p);
            return;
        }
        if (sub.equals("info") && args.length >= 2) {
            TeamData other = teams.get(args[1].toLowerCase(Locale.ROOT));
            if (other == null) p.sendMessage("§c[Team] Team '" + args[1] + "' nahi mili.");
            else teamInfo(p, other);
            return;
        }

        TeamData t = teamOf(id);
        if (t == null) {
            p.sendMessage("§c[Team] Tum kisi team me nahi ho. /createteam <name> se banao.");
            return;
        }
        boolean owner = isOwner(t, id);
        boolean mod = owner || t.admins.contains(id.toString());

        switch (sub) {
            case "info" -> teamInfo(p, t);
            case "invite" -> teamInvite(p, t, mod, args);
            case "leave" -> teamLeave(p, t, owner);
            case "kick" -> teamKick(p, t, owner, mod, args);
            case "disband" -> teamDisband(p, t, owner);
            case "color" -> teamColor(p, t, mod, args);
            case "admin" -> teamAdmin(p, t, owner, args);
            case "enderchest", "ec" -> p.openInventory(chestOf(t));
            case "sethome" -> teamSetHome(p, t, mod, args);
            case "delhome" -> teamDelHome(p, t, mod, args);
            case "ally" -> teamAlly(p, t, mod, args);
            default -> teamHelp(p);
        }
    }

    private void teamInfo(Player p, TeamData t) {
        p.sendMessage("§6[Team] " + t.color + t.name);
        p.sendMessage("§eOwner: §f" + nameOf(t.owner));
        List<String> adm = new ArrayList<>();
        for (String a : t.admins) adm.add(nameOf(a));
        List<String> mem = new ArrayList<>();
        for (String m : t.members) mem.add(nameOf(m));
        p.sendMessage("§eAdmins: §f" + (adm.isEmpty() ? "koi nahi" : String.join(", ", adm)));
        p.sendMessage("§eMembers (" + mem.size() + "): §f" + String.join(", ", mem));
        List<String> al = new ArrayList<>();
        for (String k : t.allies) {
            TeamData a = teams.get(k);
            al.add(a != null ? a.name : k);
        }
        p.sendMessage("§eAllies: §f" + (al.isEmpty() ? "koi nahi" : String.join(", ", al)));
        p.sendMessage("§eTeam homes (" + t.homes.size() + "/" + teamMaxHomes + "): §f"
                + (t.homes.isEmpty() ? "koi nahi" : String.join(", ", t.homes.keySet())));
    }

    private void teamInvite(Player p, TeamData t, boolean mod, String[] args) {
        if (!mod) {
            p.sendMessage("§c[Team] Sirf owner ya admin invite kar sakta hai.");
            return;
        }
        if (args.length < 2) {
            p.sendMessage("§c[Team] Use: /team invite <player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            p.sendMessage("§c[Team] Player '" + args[1] + "' online nahi hai.");
            return;
        }
        if (teamOf(target.getUniqueId()) != null) {
            p.sendMessage("§c[Team] " + target.getName() + " pehle se kisi team me hai.");
            return;
        }
        invites.put(target.getUniqueId(), new Invite(t.key, System.currentTimeMillis() + INVITE_TTL_MS));
        p.sendMessage("§a[Team] " + target.getName() + " ko invite bhej diya (60 sec).");
        target.sendMessage("§6[Team] §e" + p.getName() + " ne tumhe team " + t.color + t.name
                + "§e me invite kiya. §a/team accept §ekaro (60 sec).");
    }

    private void teamAccept(Player p) {
        UUID id = p.getUniqueId();
        Invite inv = invites.remove(id);
        if (inv == null || inv.expiresAt() < System.currentTimeMillis()) {
            p.sendMessage("§c[Team] Tumhare paas koi valid invite nahi hai.");
            return;
        }
        TeamData t = teams.get(inv.teamKey());
        if (t == null) {
            p.sendMessage("§c[Team] Wo team ab exist nahi karti.");
            return;
        }
        if (teamOf(id) != null) {
            p.sendMessage("§c[Team] Tum pehle se ek team me ho.");
            return;
        }
        t.members.add(id.toString());
        playerTeam.put(id, t.key);
        saveData();
        refreshPlayer(p);
        notifyTeam(t, "§a[Team] " + p.getName() + " team me aa gaya!");
    }

    private void teamLeave(Player p, TeamData t, boolean owner) {
        if (owner) {
            p.sendMessage("§c[Team] Owner team nahi chhod sakta. /team disband karo ya kisi aur ko owner banao.");
            return;
        }
        UUID id = p.getUniqueId();
        t.members.remove(id.toString());
        t.admins.remove(id.toString());
        playerTeam.remove(id);
        saveData();
        refreshPlayer(p);
        p.sendMessage("§e[Team] Tumne team " + t.name + " chhod di.");
        notifyTeam(t, "§e[Team] " + p.getName() + " ne team chhod di.");
    }

    private void teamKick(Player p, TeamData t, boolean owner, boolean mod, String[] args) {
        if (!mod) {
            p.sendMessage("§c[Team] Sirf owner ya admin kick kar sakta hai.");
            return;
        }
        if (args.length < 2) {
            p.sendMessage("§c[Team] Use: /team kick <player>");
            return;
        }
        String m = findMember(t, args[1]);
        if (m == null) {
            p.sendMessage("§c[Team] '" + args[1] + "' tumhari team me nahi hai.");
            return;
        }
        if (m.equals(t.owner)) {
            p.sendMessage("§c[Team] Owner ko kick nahi kar sakte.");
            return;
        }
        if (t.admins.contains(m) && !owner) {
            p.sendMessage("§c[Team] Admin ko sirf owner kick kar sakta hai.");
            return;
        }
        t.members.remove(m);
        t.admins.remove(m);
        UUID u = parseUuid(m);
        if (u != null) {
            playerTeam.remove(u);
            Player kicked = Bukkit.getPlayer(u);
            if (kicked != null) {
                kicked.sendMessage("§c[Team] Tumhe team " + t.name + " se nikal diya gaya.");
                refreshPlayer(kicked);
            }
        }
        saveData();
        notifyTeam(t, "§e[Team] " + nameOf(m) + " ko team se nikal diya gaya.");
        p.sendMessage("§a[Team] " + nameOf(m) + " ko kick kar diya.");
    }

    private void teamDisband(Player p, TeamData t, boolean owner) {
        if (!owner) {
            p.sendMessage("§c[Team] Sirf owner team disband kar sakta hai.");
            return;
        }
        if (t.inv != null) {
            for (HumanEntity v : new ArrayList<>(t.inv.getViewers())) v.closeInventory();
        }
        List<String> members = new ArrayList<>(t.members);
        notifyTeam(t, "§c[Team] Team " + t.name + " disband ho gayi.");
        for (String m : members) {
            UUID u = parseUuid(m);
            if (u != null) playerTeam.remove(u);
        }
        teams.remove(t.key);
        for (TeamData other : teams.values()) other.allies.remove(t.key);
        invites.values().removeIf(i -> i.teamKey().equals(t.key));
        saveData();
        for (String m : members) {
            UUID u = parseUuid(m);
            Player pl = u == null ? null : Bukkit.getPlayer(u);
            if (pl != null) refreshPlayer(pl);
        }
    }

    private void teamColor(Player p, TeamData t, boolean mod, String[] args) {
        if (!mod) {
            p.sendMessage("§c[Team] Sirf owner ya admin color change kar sakta hai.");
            return;
        }
        if (args.length < 2) {
            p.sendMessage("§c[Team] Use: /team color <red|blue|green|gold|aqua|...>");
            return;
        }
        ChatColor c;
        try {
            c = ChatColor.valueOf(args[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            c = null;
        }
        if (c == null || !c.isColor()) {
            p.sendMessage("§c[Team] Ye color valid nahi hai.");
            return;
        }
        t.color = c;
        saveData();
        refreshTeam(t);
        p.sendMessage("§a[Team] Team color ab " + c + c.name().toLowerCase(Locale.ROOT) + "§a hai.");
    }

    private void teamAdmin(Player p, TeamData t, boolean owner, String[] args) {
        if (!owner) {
            p.sendMessage("§c[Team] Sirf owner admin bana/hata sakta hai.");
            return;
        }
        if (args.length < 2) {
            p.sendMessage("§c[Team] Use: /team admin <player>");
            return;
        }
        String m = findMember(t, args[1]);
        if (m == null || m.equals(t.owner)) {
            p.sendMessage("§c[Team] Valid member nahi mila.");
            return;
        }
        if (t.admins.add(m)) {
            notifyTeam(t, "§a[Team] " + nameOf(m) + " ab admin hai.");
        } else {
            t.admins.remove(m);
            notifyTeam(t, "§e[Team] " + nameOf(m) + " ab admin nahi raha.");
        }
        saveData();
    }

    private void teamSetHome(Player p, TeamData t, boolean mod, String[] args) {
        if (!mod) {
            p.sendMessage("§c[Team] Sirf owner ya admin team home set kar sakta hai.");
            return;
        }
        String name = args.length > 1 ? args[1] : "base";
        if (!HOME_NAME.matcher(name).matches()) {
            p.sendMessage("§c[Team] Home name sirf letters, numbers, _ ya - (max 16) ho sakta hai.");
            return;
        }
        if (!t.homes.containsKey(name) && t.homes.size() >= teamMaxHomes) {
            p.sendMessage("§c[Team] Team ke max " + teamMaxHomes + " homes ho sakte hain.");
            return;
        }
        t.homes.put(name, Home.of(p.getLocation()));
        saveData();
        notifyTeam(t, "§e[Team] Team home '" + name + "' set ho gaya. (/home " + name + ")");
    }

    private void teamDelHome(Player p, TeamData t, boolean mod, String[] args) {
        if (!mod) {
            p.sendMessage("§c[Team] Sirf owner ya admin team home delete kar sakta hai.");
            return;
        }
        if (args.length < 2 || t.homes.remove(args[1]) == null) {
            p.sendMessage("§c[Team] Use: /team delhome <name> (valid team home ka naam do)");
            return;
        }
        saveData();
        p.sendMessage("§e[Team] Team home '" + args[1] + "' delete ho gaya.");
    }

    private void teamAlly(Player p, TeamData t, boolean mod, String[] args) {
        if (!mod) {
            p.sendMessage("§c[Team] Sirf owner ya admin ally set kar sakta hai.");
            return;
        }
        if (args.length < 2) {
            p.sendMessage("§c[Team] Use: /team ally <team>  (dobara likhne par ally hat jayegi)");
            return;
        }
        TeamData o = teams.get(args[1].toLowerCase(Locale.ROOT));
        if (o == null || o == t) {
            p.sendMessage("§c[Team] Valid dusri team ka naam do.");
            return;
        }
        if (t.allies.contains(o.key)) {
            t.allies.remove(o.key);
            o.allies.remove(t.key);
            notifyTeam(t, "§e[Team] " + o.name + " ab ally nahi hai.");
            notifyTeam(o, "§e[Team] " + t.name + " ab ally nahi hai.");
        } else {
            t.allies.add(o.key);
            o.allies.add(t.key);
            notifyTeam(t, "§a[Team] " + o.name + " ab tumhari ally hai!");
            notifyTeam(o, "§a[Team] " + t.name + " ab tumhari ally hai!");
        }
        saveData();
    }

    private Inventory chestOf(TeamData t) {
        if (t.inv == null) {
            TeamHolder h = new TeamHolder();
            Inventory inv = Bukkit.createInventory(h, 54,
                    LegacyComponentSerializer.legacySection().deserialize("§8Team Chest: " + t.color + t.name));
            h.inv = inv;
            if (t.chest != null) inv.setContents(t.chest);
            t.inv = inv;
        }
        return t.inv;
    }

    // =====================================================================
    //  Events
    // =====================================================================

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        refreshPlayer(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        invites.remove(p.getUniqueId());
        Scoreboard sb = mainBoard();
        if (sb != null) {
            Team t = sb.getTeam(sbName(p.getUniqueId()));
            if (t != null) t.unregister();
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof TeamHolder) saveData();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    @SuppressWarnings("deprecation")
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (netas.contains(p.getUniqueId())) {
            e.setFormat(e.getFormat().replace("%1$s", NETA_PREFIX + "%1$s"));
        }
        if (e.getMessage().toLowerCase(Locale.ROOT).contains("discord")) {
            long now = System.currentTimeMillis();
            if (now - lastDiscordMsg > 10_000L) {
                lastDiscordMsg = now;
                final String link = discordLink();
                Bukkit.getScheduler().runTask(this,
                        () -> Bukkit.broadcastMessage("§9[Discord] §bHamara Discord: §f" + link));
            }
        }
    }

    // =====================================================================
    //  Neta / team name prefix (scoreboard)
    // =====================================================================

    private static Scoreboard mainBoard() {
        return Bukkit.getScoreboardManager() == null ? null : Bukkit.getScoreboardManager().getMainScoreboard();
    }

    private static String sbName(UUID id) {
        return SB_PREFIX + id.toString().replace("-", "").substring(0, 12);
    }

    private static Component comp(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
    }

    @SuppressWarnings("deprecation")
    private void refreshPlayer(Player p) {
        UUID id = p.getUniqueId();
        boolean neta = netas.contains(id);
        TeamData tm = teamOf(id);
        Scoreboard sb = mainBoard();
        if (sb != null) {
            String tn = sbName(id);
            Team t = sb.getTeam(tn);
            if (!neta && tm == null) {
                if (t != null) t.unregister();
            } else {
                if (t == null) t = sb.registerNewTeam(tn);
                if (!t.hasEntry(p.getName())) t.addEntry(p.getName());
                String prefix = (neta ? NETA_PREFIX : "")
                        + (tm != null ? tm.color + "[" + tm.name + "] " + ChatColor.RESET : "");
                t.prefix(comp(prefix));
                t.setColor(tm != null ? tm.color : ChatColor.WHITE);
            }
        }
        if (nvPlayers.contains(id)) applyNightVision(p);
    }

    private void cleanupStray() {
        for (World w : Bukkit.getWorlds()) {
            for (TextDisplay d : w.getEntitiesByClass(TextDisplay.class)) {
                if (d.getScoreboardTags().contains(TAG)) d.remove();
            }
        }
        Scoreboard sb = mainBoard();
        if (sb != null) {
            for (Team t : new ArrayList<>(sb.getTeams())) {
                if (t.getName().startsWith(SB_PREFIX)) t.unregister();
            }
        }
    }

    // =====================================================================
    //  Hearts / hotbar indicator (per-viewer)
    // =====================================================================

    private void hudTick() {
        Map<UUID, Set<Player>> wanted = new HashMap<>();
        Map<UUID, LivingEntity> targets = new HashMap<>();

        for (UUID vid : viewers) {
            Player v = Bukkit.getPlayer(vid);
            if (v == null || !v.isOnline()) continue;
            Location eye = v.getEyeLocation();
            Vector dir = eye.getDirection();
            for (Entity e : v.getNearbyEntities(radius, radius, radius)) {
                if (!(e instanceof LivingEntity le)) continue;
                if (!wantsHud(le)) continue;
                Vector to = le.getEyeLocation().toVector().subtract(eye.toVector());
                double len = to.length();
                if (len < 0.001 || len > radius) continue;
                if (to.clone().multiply(1.0 / len).dot(dir) < viewRange) continue;
                wanted.computeIfAbsent(le.getUniqueId(), k -> new HashSet<>()).add(v);
                targets.put(le.getUniqueId(), le);
            }
        }

        Iterator<Map.Entry<String, Holder>> it = holders.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Holder> en = it.next();
            UUID id = parseUuid(en.getKey());
            if (id == null || !wanted.containsKey(id)) {
                en.getValue().removeAll();
                it.remove();
            }
        }
        for (Map.Entry<UUID, Set<Player>> en : wanted.entrySet()) {
            LivingEntity t = targets.get(en.getKey());
            Holder h = holders.computeIfAbsent(en.getKey().toString(), k -> new Holder());
            updateHolder(h, t, en.getValue());
        }
    }

    private boolean wantsHud(LivingEntity le) {
        if (le.isDead() || !le.isValid()) return false;
        if (le instanceof Player pl) {
            if (pl.getGameMode() == org.bukkit.GameMode.SPECTATOR) return false;
            return showPlayerHearts || showHotbar;
        }
        if (le instanceof org.bukkit.entity.ArmorStand) return false;
        if (!showMobHearts) return false;
        if (onlyDamaged) return le.getHealth() < maxHealthOf(le);
        return true;
    }

    private static double maxHealthOf(LivingEntity le) {
        var attr = le.getAttribute(Attribute.MAX_HEALTH);
        return attr != null ? attr.getValue() : 20.0;
    }

    private void updateHolder(Holder h, LivingEntity t, Set<Player> viewersNow) {
        boolean isPlayer = t instanceof Player;
        boolean wantHearts = isPlayer ? showPlayerHearts : showMobHearts;
        boolean wantHotbar = isPlayer && showHotbar;
        Location top = t.getLocation().add(0, t.getHeight(), 0);
        boolean fresh = false;

        if (wantHearts) {
            Location l = top.clone().add(0, isPlayer ? playerHeartsY : mobHeartsY, 0);
            if (h.hearts == null || !h.hearts.isValid()) {
                h.hearts = spawnDisplay(l);
                h.lastHearts = "";
                fresh = true;
            } else {
                h.hearts.teleport(l);
            }
            String txt = heartsText(t);
            if (!txt.equals(h.lastHearts)) {
                h.hearts.text(comp(txt));
                h.lastHearts = txt;
            }
        } else if (h.hearts != null) {
            h.hearts.remove();
            h.hearts = null;
        }

        if (wantHotbar) {
            Location l = top.clone().add(0, hotbarY, 0);
            if (h.hotbar == null || !h.hotbar.isValid()) {
                h.hotbar = spawnDisplay(l);
                h.lastHotbar = "";
                fresh = true;
            } else {
                h.hotbar.teleport(l);
            }
            String txt = hotbarText((Player) t);
            if (!txt.equals(h.lastHotbar)) {
                h.hotbar.text(comp(txt));
                h.lastHotbar = txt;
            }
        } else if (h.hotbar != null) {
            h.hotbar.remove();
            h.hotbar = null;
        }

        if (fresh) h.shown.clear();

        Iterator<UUID> it = h.shown.iterator();
        while (it.hasNext()) {
            UUID vid = it.next();
            Player v = Bukkit.getPlayer(vid);
            if (v == null || !viewersNow.contains(v)) {
                if (v != null) {
                    if (h.hearts != null) v.hideEntity(this, h.hearts);
                    if (h.hotbar != null) v.hideEntity(this, h.hotbar);
                }
                it.remove();
            }
        }
        for (Player v : viewersNow) {
            if (h.shown.add(v.getUniqueId())) {
                if (h.hearts != null) v.showEntity(this, h.hearts);
                if (h.hotbar != null) v.showEntity(this, h.hotbar);
            }
        }
    }

    private TextDisplay spawnDisplay(Location loc) {
        return loc.getWorld().spawn(loc, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setVisibleByDefault(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setDefaultBackground(false);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
            d.setTeleportDuration(Math.min(59, period));
            d.setTransformation(new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(),
                    new org.joml.AxisAngle4f(),
                    new org.joml.Vector3f(0.6f, 0.6f, 0.6f),
                    new org.joml.AxisAngle4f()));
        });
    }

    private static String heartsText(LivingEntity t) {
        double hp = Math.max(0, t.getHealth());
        double max = maxHealthOf(t);
        int total = (int) Math.ceil(max / 2.0);
        if (total > 10) {
            return "§c\u2764 " + (int) Math.ceil(hp / 2.0) + "§7/" + total;
        }
        int full = (int) Math.floor(hp / 2.0);
        boolean half = hp - full * 2.0 >= 1.0;
        int empty = Math.max(0, total - full - (half ? 1 : 0));
        StringBuilder sb = new StringBuilder();
        if (full > 0) sb.append("§c").append("\u2764".repeat(full));
        if (half) sb.append("§6\u2764");
        if (empty > 0) sb.append("§8").append("\u2764".repeat(empty));
        return sb.toString();
    }

    private static String hotbarText(Player p) {
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        int sel = inv.getHeldItemSlot();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            ItemStack it = inv.getItem(i);
            if (i > 0) sb.append(" §8\u00b7 ");
            if (it == null || it.getType().isAir()) {
                sb.append("§8-");
                continue;
            }
            String[] parts = it.getType().name().toLowerCase(Locale.ROOT).split("_");
            String nm = parts[parts.length - 1];
            if (nm.length() > 7) nm = nm.substring(0, 7);
            sb.append(i == sel ? "§e§l" : "§f").append(nm);
            if (it.getAmount() > 1) sb.append("§7x").append(it.getAmount());
        }
        return sb.toString();
    }

    private void removeAllHolders() {
        for (Holder h : holders.values()) h.removeAll();
        holders.clear();
    }

    // =====================================================================
    //  Data types
    // =====================================================================

    private record Home(String world, double x, double y, double z, float yaw, float pitch) {
        static Home of(Location l) {
            return new Home(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
        }

        Location toLocation(World w) {
            return new Location(w, x, y, z, yaw, pitch);
        }
    }

    private record Invite(String teamKey, long expiresAt) {
    }

    private static final class TeamData {
        final String key;
        final String name;
        final String owner;
        ChatColor color = ChatColor.WHITE;
        final Set<String> admins = new LinkedHashSet<>();
        final Set<String> members = new LinkedHashSet<>();
        final Set<String> allies = new LinkedHashSet<>();
        final Map<String, Home> homes = new LinkedHashMap<>();
        ItemStack[] chest;
        Inventory inv;

        TeamData(String name, String owner) {
            this.name = name;
            this.key = name.toLowerCase(Locale.ROOT);
            this.owner = owner;
            if (!owner.isEmpty()) members.add(owner);
        }

        ItemStack[] snapshot() {
            return inv != null ? inv.getContents() : chest;
        }
    }

    private static final class TeamHolder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final class Holder {
        TextDisplay hearts;
        TextDisplay hotbar;
        final Set<UUID> shown = new HashSet<>();
        String lastHearts = "";
        String lastHotbar = "";

        void removeAll() {
            if (hearts != null && hearts.isValid()) hearts.remove();
            if (hotbar != null && hotbar.isValid()) hotbar.remove();
            hearts = null;
            hotbar = null;
            shown.clear();
        }
    }
}
