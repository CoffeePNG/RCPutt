package com.redcoffee.puttputt.item;

import com.redcoffee.puttputt.config.ItemDefinition;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Builds the putter and the ball model, and tags the putter so a stray bow in someone's inventory
 * is never mistaken for one.
 */
public final class PuttItems {

    private final Plugin plugin;
    private final NamespacedKey putterKey;

    public PuttItems(Plugin plugin) {
        this.plugin = plugin;
        this.putterKey = new NamespacedKey(plugin, "putter");
    }

    public ItemStack createPutter(ItemDefinition definition) {
        ItemStack stack = build(definition, Material.BOW);
        stack.editPersistentDataContainer(container ->
                container.set(putterKey, PersistentDataType.BYTE, (byte) 1));
        // An unbreakable putter avoids a bow that quietly dies mid-round.
        stack.setData(DataComponentTypes.UNBREAKABLE);
        return stack;
    }

    public ItemStack createBallItem(ItemDefinition definition) {
        return build(definition, Material.SNOWBALL);
    }

    public boolean isPutter(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.getPersistentDataContainer().has(putterKey, PersistentDataType.BYTE);
    }

    /** Removes every putter from a player's inventory - used when they leave a round. */
    public void stripPutters(org.bukkit.entity.Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isPutter(contents[slot])) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    public void applyBallModel(ItemDisplay display, ItemDefinition definition) {
        display.setItemStack(createBallItem(definition));
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
        display.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
        display.setPersistent(false);
    }

    private ItemStack build(ItemDefinition definition, Material fallback) {
        Material material = Material.matchMaterial(definition.material());
        if (material == null) {
            plugin.getLogger().warning("Unknown item material '" + definition.material()
                    + "'; falling back to " + fallback + ".");
            material = fallback;
        }
        ItemStack stack = new ItemStack(material);
        if (definition.displayName() != null) {
            stack.setData(DataComponentTypes.CUSTOM_NAME,
                    MiniMessage.miniMessage().deserialize(definition.displayName()));
        }
        if (definition.modelFloat() != null || definition.modelString() != null) {
            CustomModelData.Builder model = CustomModelData.customModelData();
            if (definition.modelFloat() != null) {
                model.addFloat(definition.modelFloat());
            }
            if (definition.modelString() != null) {
                model.addString(definition.modelString());
            }
            stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, model);
        }
        return stack;
    }
}
