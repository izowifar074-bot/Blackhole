package com.github.izowifar.blackhole;

import java.util.List;

import com.github.izowifar.blackhole.entity.BlackHoleEntity;
import com.github.izowifar.blackhole.item.BlackHoleItem;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemLore;

public class BlackholeMod implements ModInitializer {
    public static final String MOD_ID = "blackhole";

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static final EntityType<BlackHoleEntity> BLACK_HOLE = Registry.register(
            BuiltInRegistries.ENTITY_TYPE, id("black_hole"),
            EntityType.Builder.<BlackHoleEntity>of(BlackHoleEntity::new, MobCategory.MISC)
                    .sized(1.0f, 1.0f)
                    .fireImmune()
                    .clientTrackingRange(16)
                    .updateInterval(2)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, id("black_hole"))));

    public static final Item SINGULARITY_CORE = Registry.register(
            BuiltInRegistries.ITEM, id("singularity_core"),
            new BlackHoleItem(new Item.Properties()
                    .setId(ResourceKey.create(Registries.ITEM, id("singularity_core")))
                    .stacksTo(16)
                    .rarity(Rarity.EPIC)
                    .component(DataComponents.LORE, new ItemLore(List.of(
                            Component.translatable("item.blackhole.singularity_core.desc"))))));

    @Override
    public void onInitialize() {
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
                .register(entries -> entries.accept(SINGULARITY_CORE));
    }
}
