package dev.minedraft.lib.client;

import net.neoforged.fml.ModList;

/** 셰이더 팩(Iris/Oculus) 사용 여부. 편집창 그래픽이 깨지므로 열 때 경고한다. */
public final class ShaderPacks {
    private ShaderPacks() {}

    private static Boolean irisPresent;

    /** Iris·Oculus 셰이더 팩이 지금 켜져 있는가. 모드가 없거나 확인할 수 없으면 false. */
    public static boolean inUse() {
        try {
            if (irisPresent == null) irisPresent = ModList.get().isLoaded("iris") || ModList.get().isLoaded("oculus");
            if (!irisPresent) return false;
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            return (Boolean) api.getMethod("isShaderPackInUse").invoke(inst);
        } catch (Throwable t) {
            return false;
        }
    }
}
