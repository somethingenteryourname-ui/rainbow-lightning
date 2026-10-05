package me.rainbowlightning;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class RodItem {

    private final NamespacedKey key;

    public RodItem(JavaPlugin plugin) {
        this.key = new NamespacedKey(plugin, "rainbow_rod");
    }

    public ItemStack create() {
        MiniMessage mm = MiniMessage.miniMessage();
        ItemStack item = new ItemStack(Material.LIGHTNING_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(mm.deserialize("<!italic><bold><rainbow>Rainbow Storm Rod</rainbow></bold>"));
        meta.lore(List.of(
                mm.deserialize("<!italic><gray>Hold <white>right-click</white> to charge,"),
                mm.deserialize("<!italic><gray>then unleash a <rainbow>prismatic lightning beam</rainbow><gray>.")
        ));
        meta.setEnchantmentGlintOverride(true);
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isRod(ItemStack item) {
        if (item == null || item.getType() != Material.LIGHTNING_ROD || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }
}
