package com.redcoffee.puttputt.item;

import com.redcoffee.puttputt.config.ItemDefinition;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
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

    /**
     * Charge duration ceiling. Long enough that holding the putter can never actually finish the
     * "consume" and destroy the item, short of someone holding right-click for an hour.
     */
    private static final float HOLD_SECONDS = 3600.0f;

    private final Plugin plugin;
    private final NamespacedKey putterKey;
    private final NamespacedKey wandKey;

    public PuttItems(Plugin plugin) {
        this.plugin = plugin;
        this.putterKey = new NamespacedKey(plugin, "putter");
        this.wandKey = new NamespacedKey(plugin, "wand");
    }

    /** The course-builder wand. Tagged in the PDC so an ordinary blaze rod is never mistaken for one. */
    public ItemStack createWand(ItemDefinition definition) {
        ItemStack stack = build(definition, Material.BLAZE_ROD);
        stack.editPersistentDataContainer(container ->
                container.set(wandKey, PersistentDataType.BYTE, (byte) 1));
        return stack;
    }

    public boolean isWand(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    /**
     * Builds the putter: a shovel that the player holds right-click on to charge.
     *
     * <p>A shovel has no vanilla use animation, so there is nothing to hang a "still holding"
     * signal off. Giving it a {@code consumable} component with a very long duration and no
     * animation makes the server treat right-click-hold as an in-progress use, which is what makes
     * {@code Player.isHandRaised()} true for as long as the button is down. That is the signal the
     * charge loop polls; the duration is long enough that the "consume" can never complete, and
     * {@code PuttListener} cancels the consume event as a second line of defence.
     */
    public ItemStack createPutter(ItemDefinition definition) {
        ItemStack stack = build(definition, Material.IRON_SHOVEL);
        stack.editPersistentDataContainer(container ->
                container.set(putterKey, PersistentDataType.BYTE, (byte) 1));
        // An unbreakable putter avoids a tool that quietly dies mid-round.
        stack.setData(DataComponentTypes.UNBREAKABLE);
        stack.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
                .consumeSeconds(HOLD_SECONDS)
                .animation(ItemUseAnimation.NONE)
                .hasConsumeParticles(false));
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

    public boolean hasPutter(org.bukkit.entity.Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isPutter(stack)) {
                return true;
            }
        }
        return false;
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
