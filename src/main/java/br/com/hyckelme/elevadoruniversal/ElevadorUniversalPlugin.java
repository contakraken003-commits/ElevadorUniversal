package br.com.hyckelme.elevadoruniversal;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

public final class ElevadorUniversalPlugin extends JavaPlugin implements Listener {
    private static final String GREEN = "green";
    private static final String RED = "red";

    private NamespacedKey itemTypeKey;
    private NamespacedKey itemOwnerKey;
    private final Map<String, ElevatorData> elevators = new HashMap<>();
    // Registros cujos mundos ainda não estão carregados são preservados até poderem ser recuperados.
    private final Map<String, Map<String, Object>> unresolvedElevators = new HashMap<>();
    private final Map<UUID, Long> lastTeleport = new HashMap<>();
    private File dataFile;
    private YamlConfiguration dataConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemTypeKey = new NamespacedKey(this, "elevator_type");
        itemOwnerKey = new NamespacedKey(this, "elevator_owner");
        dataFile = new File(getDataFolder(), "elevators.yml");
        loadElevators();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Elevador Universal 0.2.0 iniciado. Elevadores carregados: " + elevators.size());
    }

    @Override
    public void onDisable() {
        saveElevators();
        getLogger().info("Elevador Universal desligado.");
    }

    // Comando de entrega para testes e integração com lojas que aceitam comandos de console.
    // Exemplo de comando na loja: elevador give {player}
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("elevador")) return false;

        if (args.length == 0 || args[0].equalsIgnoreCase("ajuda")) {
            sender.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "Elevador Universal");
            sender.sendMessage(ChatColor.WHITE + "/elevador status " + ChatColor.GRAY + "- estado do plugin");
            sender.sendMessage(ChatColor.WHITE + "/elevador give <jogador> " + ChatColor.GRAY + "- entrega um par (admin)");
            return true;
        }

        if (args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(ChatColor.RED + "[Elevador] " + ChatColor.GREEN + "Plugin ativo.");
            sender.sendMessage(ChatColor.GRAY + "Elevadores registrados: " + elevators.size());
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("elevador.admin")) {
                sender.sendMessage(ChatColor.RED + "Você não tem permissão para isso.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Uso: /elevador give <jogador>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Jogador não encontrado ou offline.");
                return true;
            }
            giveElevatorPair(target);
            sender.sendMessage(ChatColor.GREEN + "Par de elevador entregue para " + target.getName() + ".");
            target.sendMessage(ChatColor.GREEN + "Você recebeu um elevador: bloco verde para subir e vermelho para descer.");
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Comando desconhecido. Use /elevador ajuda.");
        return true;
    }

    private void giveElevatorPair(Player player) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(createElevatorItem(player.getUniqueId(), GREEN),
                createElevatorItem(player.getUniqueId(), RED));
        leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
    }

    private ItemStack createElevatorItem(UUID owner, String type) {
        Material material = type.equals(GREEN) ? Material.GREEN_CONCRETE : Material.RED_CONCRETE;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (type.equals(GREEN)) {
                meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "Elevador - SUBIR");
                meta.setLore(List.of(
                        ChatColor.GRAY + "Bloco inferior do elevador",
                        ChatColor.WHITE + "Fique completamente em cima e aperte ESPAÇO.",
                        ChatColor.DARK_GRAY + "Item vinculado ao comprador"
                ));
            } else {
                meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Elevador - DESCER");
                meta.setLore(List.of(
                        ChatColor.GRAY + "Bloco superior do elevador",
                        ChatColor.WHITE + "Fique completamente em cima e aperte SHIFT.",
                        ChatColor.DARK_GRAY + "Item vinculado ao comprador"
                ));
            }
            meta.getPersistentDataContainer().set(itemTypeKey, PersistentDataType.STRING, type);
            meta.getPersistentDataContainer().set(itemOwnerKey, PersistentDataType.STRING, owner.toString());
            item.setItemMeta(meta);
        }
        return item;
    }

    private String getItemType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(itemTypeKey, PersistentDataType.STRING);
    }

    private UUID getItemOwner(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String raw = item.getItemMeta().getPersistentDataContainer().get(itemOwnerKey, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @EventHandler
    public void onElevatorPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        String type = getItemType(item);
        if (type == null || (!type.equals(GREEN) && !type.equals(RED))) return;

        Player player = event.getPlayer();
        UUID owner = getItemOwner(item);
        if (owner == null || !owner.equals(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "Este bloco de elevador não pertence a você.");
            return;
        }

        Block placed = event.getBlockPlaced();
        Location loc = placed.getLocation();
        int band = getBand(loc.getBlockY());
        String key = keyFor(loc, band);

        ElevatorData data = elevators.get(key);
        if (data == null) {
            data = new ElevatorData(owner, loc.getWorld().getName(), loc.getChunk().getX(),
                    loc.getChunk().getZ(), band);
            setBlockLocation(data, type, loc);
            elevators.put(key, data);
            saveElevators();
            player.sendMessage(ChatColor.GREEN + (type.equals(GREEN)
                    ? "Bloco verde registrado. Coloque o bloco vermelho acima, no mesmo chunk e faixa de altura."
                    : "Bloco vermelho registrado. Coloque o bloco verde abaixo, no mesmo chunk e faixa de altura."));
            return;
        }

        if (!data.owner.equals(owner)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "Já existe um elevador neste chunk e nesta faixa de 64 blocos.");
            return;
        }

        if ((type.equals(GREEN) && data.green != null) || (type.equals(RED) && data.red != null)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "Este elevador já possui esse bloco. Use o bloco oposto para completar o par.");
            return;
        }

        Location greenLoc = type.equals(GREEN) ? loc : data.green;
        Location redLoc = type.equals(RED) ? loc : data.red;
        if (greenLoc != null && redLoc != null) {
            if (!greenLoc.getWorld().equals(redLoc.getWorld())
                    || greenLoc.getChunk().getX() != redLoc.getChunk().getX()
                    || greenLoc.getChunk().getZ() != redLoc.getChunk().getZ()
                    || getBand(greenLoc.getBlockY()) != getBand(redLoc.getBlockY())) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "Os dois blocos precisam ficar no mesmo chunk e na mesma faixa de 64 blocos.");
                return;
            }
            if (greenLoc.getBlockY() >= redLoc.getBlockY()) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "Posicionamento incorreto: o bloco VERDE deve ficar abaixo do VERMELHO.");
                return;
            }
            if (greenLoc.getBlockX() == redLoc.getBlockX()
                    && greenLoc.getBlockY() == redLoc.getBlockY()
                    && greenLoc.getBlockZ() == redLoc.getBlockZ()) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "Os blocos verde e vermelho precisam ocupar posições diferentes.");
                return;
            }
        }

        setBlockLocation(data, type, loc);
        elevators.put(key, data);
        saveElevators();
        if (data.green != null && data.red != null) {
            player.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "Elevador configurado com sucesso!");
            player.sendMessage(ChatColor.GRAY + "Verde: subir com ESPAÇO. Vermelho: descer com SHIFT.");
        } else {
            player.sendMessage(ChatColor.YELLOW + "Bloco registrado. Agora coloque o bloco oposto para completar o elevador.");
        }
    }

    private void setBlockLocation(ElevatorData data, String type, Location loc) {
        if (type.equals(GREEN)) data.green = loc.clone();
        else data.red = loc.clone();
    }


    @EventHandler
    public void onElevatorBreak(BlockBreakEvent event) {
        Location loc = event.getBlock().getLocation();
        ElevatorData data = findElevatorAt(loc);
        if (data == null) return;

        Player player = event.getPlayer();
        if (!data.owner.equals(player.getUniqueId()) && !player.hasPermission("elevador.admin")) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "Você não pode quebrar o elevador de outro jogador.");
            return;
        }

        event.setCancelled(true);
        Location greenLoc = data.green == null ? null : data.green.clone();
        Location redLoc = data.red == null ? null : data.red.clone();

        // Só devolve itens correspondentes aos blocos físicos que realmente existem.
        // Isso evita duplicar o bloco oposto quando o elevador ainda está incompleto.
        boolean returnGreen = greenLoc != null
                && greenLoc.getBlock().getType() == Material.GREEN_CONCRETE;
        boolean returnRed = redLoc != null
                && redLoc.getBlock().getType() == Material.RED_CONCRETE;

        if (returnGreen) greenLoc.getBlock().setType(Material.AIR, false);
        if (returnRed) redLoc.getBlock().setType(Material.AIR, false);

        removeElevator(data);

        Player owner = Bukkit.getPlayer(data.owner);
        World dropWorld = loc.getWorld();
        if (owner != null) {
            List<ItemStack> leftovers = new ArrayList<>();
            if (returnGreen) leftovers.addAll(owner.getInventory().addItem(createElevatorItem(data.owner, GREEN)).values());
            if (returnRed) leftovers.addAll(owner.getInventory().addItem(createElevatorItem(data.owner, RED)).values());
            for (ItemStack leftover : leftovers) {
                if (dropWorld != null) dropWorld.dropItemNaturally(loc, leftover);
            }
            owner.sendMessage(ChatColor.YELLOW + (returnGreen || returnRed
                    ? "Elevador removido. Foram devolvidos somente os blocos existentes."
                    : "Registro removido; nenhum bloco físico foi encontrado para devolver."));
        } else if (dropWorld != null) {
            if (returnGreen) dropWorld.dropItemNaturally(loc, createElevatorItem(data.owner, GREEN));
            if (returnRed) dropWorld.dropItemNaturally(loc, createElevatorItem(data.owner, RED));
        }

        saveElevators();
        player.sendMessage(ChatColor.YELLOW + "Elevador removido.");
    }

    /** Impede explosões de remover fisicamente blocos que continuam registrados como elevadores. */
    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> findElevatorAt(block.getLocation()) != null);
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> findElevatorAt(block.getLocation()) != null);
    }

    /** Blocos de elevador são fixos: pistões não podem movê-los. */
    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> findElevatorAt(block.getLocation()) != null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> findElevatorAt(block.getLocation()) != null)) {
            event.setCancelled(true);
        }
    }

    /** Evita que entidades capazes de alterar blocos invalidem um elevador registrado. */
    @EventHandler
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (findElevatorAt(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    /** Recupera registros preservados quando o mundo correspondente é carregado. */
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        String loadedWorld = event.getWorld().getName();
        Iterator<Map.Entry<String, Map<String, Object>>> iterator = unresolvedElevators.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Map<String, Object>> entry = iterator.next();
            Map<String, Object> values = entry.getValue();
            Object worldValue = values.get("world");
            if (worldValue != null && loadedWorld.equals(String.valueOf(worldValue))) {
                ConfigurationSection section = dataConfig.createSection("__pending_load__");
                for (Map.Entry<String, Object> value : values.entrySet()) {
                    section.set(value.getKey(), value.getValue());
                }
                ElevatorData recovered = parseElevator(entry.getKey(), section);
                dataConfig.set("__pending_load__", null);
                if (recovered != null) {
                    elevators.put(entry.getKey(), recovered);
                    iterator.remove();
                    getLogger().info("Elevador recuperado após carregar o mundo: " + entry.getKey());
                }
            }
        }
        if (!unresolvedElevators.isEmpty()) saveElevators();
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to.getY() <= from.getY()) return;
        if (to.getY() - from.getY() > 1.0) return;

        Player player = event.getPlayer();
        if (player.getVelocity().getY() < 0.2) return;

        Block support = findElevatorSupport(from, GREEN);
        if (support == null) return;

        ElevatorData data = findElevatorAt(support.getLocation());
        if (data == null || data.green == null || data.red == null) {
            player.sendMessage(ChatColor.RED + "Este elevador ainda não está completo.");
            return;
        }
        teleportUsingElevator(player, data, RED);
    }

    @EventHandler
    public void onPlayerSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return;
        Player player = event.getPlayer();
        Location feet = player.getLocation();
        Block support = findElevatorSupport(feet, RED);
        if (support == null) return;

        ElevatorData data = findElevatorAt(support.getLocation());
        if (data == null || data.green == null || data.red == null) {
            player.sendMessage(ChatColor.RED + "Este elevador ainda não está completo.");
            return;
        }
        teleportUsingElevator(player, data, GREEN);
    }

    private void teleportUsingElevator(Player player, ElevatorData data, String destinationType) {
        long now = System.currentTimeMillis();
        long cooldown = getConfig().getLong("teleport-cooldown-ms", 900L);
        Long last = lastTeleport.get(player.getUniqueId());
        if (last != null && now - last < cooldown) return;

        Location destinationBlock = destinationType.equals(GREEN) ? data.green : data.red;
        if (destinationBlock == null || destinationBlock.getWorld() == null) {
            player.sendMessage(ChatColor.RED + "O destino do elevador não está disponível.");
            return;
        }

        Block destination = destinationBlock.getBlock();
        if (!isElevatorMaterial(destination, destinationType)) {
            player.sendMessage(ChatColor.RED + "O bloco de destino foi alterado. O elevador precisa ser reconstruído.");
            removeElevator(data);
            saveElevators();
            return;
        }

        Location target = new Location(destination.getWorld(),
                destination.getX() + 0.5,
                destination.getY() + 1.0,
                destination.getZ() + 0.5,
                player.getLocation().getYaw(),
                player.getLocation().getPitch());

        if (!target.getBlock().isPassable() || !target.clone().add(0, 1, 0).getBlock().isPassable()) {
            player.sendMessage(ChatColor.RED + "Não há espaço seguro no destino do elevador.");
            return;
        }

        lastTeleport.put(player.getUniqueId(), now);
        player.setVelocity(new Vector(0, 0, 0));
        player.teleport(target);
        player.setFallDistance(0);
        player.sendMessage(ChatColor.GREEN + (destinationType.equals(RED)
                ? "Elevador: subindo!"
                : "Elevador: descendo!"));
    }

    /**
     * Encontra um bloco de elevador sob qualquer parte da hitbox horizontal do jogador.
     * Não exige que o centro do jogador esteja dentro do bloco: basta que a hitbox
     * se sobreponha ao bloco e que os pés estejam aproximadamente na altura do topo.
     */
    private Block findElevatorSupport(Location feet, String type) {
        if (feet.getWorld() == null) return null;

        final double halfWidth = 0.30; // largura horizontal aproximada da hitbox do jogador
        final double verticalTolerance = 0.18;
        final double x = feet.getX();
        final double z = feet.getZ();
        final double y = feet.getY();

        int baseY = (int) Math.floor(y - 0.01);
        int centerX = (int) Math.floor(x);
        int centerZ = (int) Math.floor(z);

        for (int bx = centerX - 1; bx <= centerX + 1; bx++) {
            for (int bz = centerZ - 1; bz <= centerZ + 1; bz++) {
                Block candidate = feet.getWorld().getBlockAt(bx, baseY, bz);
                if (!isElevatorMaterial(candidate, type)) continue;

                boolean overlapsX = x + halfWidth > bx && x - halfWidth < bx + 1.0;
                boolean overlapsZ = z + halfWidth > bz && z - halfWidth < bz + 1.0;
                boolean feetAtTop = Math.abs(y - (byTop(candidate))) <= verticalTolerance;
                if (overlapsX && overlapsZ && feetAtTop) return candidate;
            }
        }
        return null;
    }

    private double byTop(Block block) {
        return block.getY() + 1.0;
    }

    private boolean isElevatorMaterial(Block block, String type) {
        return block.getType() == (type.equals(GREEN) ? Material.GREEN_CONCRETE : Material.RED_CONCRETE);
    }

    private ElevatorData findElevatorAt(Location loc) {
        if (loc.getWorld() == null) return null;
        for (ElevatorData data : elevators.values()) {
            if (sameBlock(data.green, loc) || sameBlock(data.red, loc)) return data;
        }
        return null;
    }

    private boolean sameBlock(Location a, Location b) {
        return a != null && b != null && a.getWorld() != null && b.getWorld() != null
                && a.getWorld().getUID().equals(b.getWorld().getUID())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    private void removeElevator(ElevatorData data) {
        elevators.remove(keyFor(data.worldName, data.chunkX, data.chunkZ, data.band));
    }

    private int getBand(int y) {
        int size = Math.max(1, getConfig().getInt("height-band-size", 64));
        return Math.floorDiv(y, size);
    }

    private String keyFor(Location loc, int band) {
        return keyFor(loc.getWorld().getName(), loc.getChunk().getX(), loc.getChunk().getZ(), band);
    }

    private String keyFor(String world, int chunkX, int chunkZ, int band) {
        return world + ":" + chunkX + ":" + chunkZ + ":" + band;
    }

    private void loadElevators() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Não foi possível criar a pasta de dados.");
        }
        if (!dataFile.exists()) {
            dataConfig = new YamlConfiguration();
            return;
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = dataConfig.getConfigurationSection("elevators");
        if (root == null) return;

        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) continue;
            try {
                ElevatorData data = parseElevator(key, section);
                if (data != null) {
                    elevators.put(key, data);
                } else {
                    // Não descarte registros só porque um mundo ainda não está disponível.
                    unresolvedElevators.put(key, new LinkedHashMap<>(section.getValues(true)));
                    getLogger().warning("Elevador preservado até o mundo ficar disponível: " + key);
                }
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Registro de elevador inválido ignorado: " + key);
            }
        }
    }

    private ElevatorData parseElevator(String key, ConfigurationSection section) {
        UUID owner = UUID.fromString(section.getString("owner", ""));
        String worldName = section.getString("world", "");
        if (worldName.isBlank()) throw new IllegalArgumentException("Mundo ausente");
        int chunkX = section.getInt("chunk-x");
        int chunkZ = section.getInt("chunk-z");
        int band = section.getInt("band");

        ConfigurationSection greenSection = section.getConfigurationSection("green");
        ConfigurationSection redSection = section.getConfigurationSection("red");
        boolean greenExpected = greenSection != null;
        boolean redExpected = redSection != null;
        Location green = readLocation(greenSection);
        Location red = readLocation(redSection);

        if ((greenExpected && green == null) || (redExpected && red == null)) return null;
        if (green == null && red == null) return null;

        ElevatorData data = new ElevatorData(owner, worldName, chunkX, chunkZ, band);
        data.green = green;
        data.red = red;
        return data;
    }

    private Location readLocation(ConfigurationSection section) {
        if (section == null) return null;
        String worldName = section.getString("world", "");
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, section.getInt("x"), section.getInt("y"), section.getInt("z"));
    }

    private void saveElevators() {
        if (dataConfig == null) dataConfig = new YamlConfiguration();
        dataConfig.set("elevators", null);
        for (Map.Entry<String, ElevatorData> entry : elevators.entrySet()) {
            String path = "elevators." + entry.getKey();
            ElevatorData data = entry.getValue();
            dataConfig.set(path + ".owner", data.owner.toString());
            dataConfig.set(path + ".world", data.worldName);
            dataConfig.set(path + ".chunk-x", data.chunkX);
            dataConfig.set(path + ".chunk-z", data.chunkZ);
            dataConfig.set(path + ".band", data.band);
            writeLocation(path + ".green", data.green);
            writeLocation(path + ".red", data.red);
        }
        // Regrava os registros não resolvidos sem perder coordenadas de mundos não carregados.
        for (Map.Entry<String, Map<String, Object>> entry : unresolvedElevators.entrySet()) {
            String base = "elevators." + entry.getKey();
            for (Map.Entry<String, Object> value : entry.getValue().entrySet()) {
                dataConfig.set(base + "." + value.getKey(), value.getValue());
            }
        }

        File tempFile = new File(getDataFolder(), "elevators.yml.tmp");
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
                throw new IOException("Não foi possível criar a pasta de dados.");
            }
            dataConfig.save(tempFile);
            try {
                Files.move(tempFile.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(tempFile.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            getLogger().severe("Falha ao salvar elevadores: " + ex.getMessage());
            if (tempFile.exists() && !tempFile.delete()) {
                getLogger().warning("Não foi possível remover o arquivo temporário " + tempFile.getName());
            }
        }
    }

    private void writeLocation(String path, Location loc) {
        dataConfig.set(path, null);
        if (loc == null || loc.getWorld() == null) return;
        dataConfig.set(path + ".world", loc.getWorld().getName());
        dataConfig.set(path + ".x", loc.getBlockX());
        dataConfig.set(path + ".y", loc.getBlockY());
        dataConfig.set(path + ".z", loc.getBlockZ());
    }

    private static final class ElevatorData {
        private final UUID owner;
        private final String worldName;
        private final int chunkX;
        private final int chunkZ;
        private final int band;
        private Location green;
        private Location red;

        private ElevatorData(UUID owner, String worldName, int chunkX, int chunkZ, int band) {
            this.owner = owner;
            this.worldName = worldName;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.band = band;
        }
    }
}
