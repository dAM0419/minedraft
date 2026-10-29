package dev.minedraft.lib.edit;

import net.minecraft.resources.ResourceLocation;

/** 되돌리기 기록 한 줄. 영역(domain)마다 {@link UndoInverter} 가 거꾸로 적용할 연산을 만든다. */
public interface UndoChange {
    ResourceLocation domain();
}
