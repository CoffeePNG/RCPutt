package com.redcoffee.puttputt.config;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Config-driven item description for the ball and the putter.
 *
 * <p>Models are selected with {@code custom_model_data}, not {@code item_model} overrides: LabyMod
 * and some other clients render {@code custom_model_data} reliably but ignore {@code item_model}
 * (the same finding that drove WeaponMechanics and RCPhone). In 1.21.4+ the component carries
 * lists rather than a single int, so both a float and a string channel are exposed here.
 */
public record ItemDefinition(String material, Float modelFloat, String modelString, String displayName) {

    public static ItemDefinition read(ConfigurationSection section, String fallbackMaterial, String fallbackName) {
        if (section == null) {
            return new ItemDefinition(fallbackMaterial, null, null, fallbackName);
        }
        Float modelFloat = section.contains("custom_model_data")
                ? (float) section.getDouble("custom_model_data")
                : null;
        String modelString = section.getString("custom_model_string");
        return new ItemDefinition(
                section.getString("material", fallbackMaterial),
                modelFloat,
                modelString == null || modelString.isBlank() ? null : modelString,
                section.getString("name", fallbackName));
    }
}
