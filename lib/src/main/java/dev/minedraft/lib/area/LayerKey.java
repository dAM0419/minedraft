package dev.minedraft.lib.area;

import net.minecraft.resources.ResourceLocation;

/** 층을 꺼내는 열쇠: ID 와 클래스. */
public record LayerKey<T extends AreaLayer>(ResourceLocation id, Class<T> type) {}
