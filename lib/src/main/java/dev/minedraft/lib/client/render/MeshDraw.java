package dev.minedraft.lib.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.Matrix4f;

/** 구운 정점 버퍼 그리기 도우미. 조명은 균일한 흰 조명맵으로 바꿔 밤에도 밝게 보인다. */
public final class MeshDraw {
    private MeshDraw() {}

    private static DynamicTexture white;
    private static float savedFogStart, savedFogEnd;
    private static FogShape savedFogShape = FogShape.SPHERE;

    private static int whiteLightmap() {
        if (white == null) {
            white = new DynamicTexture(16, 16, false);
            NativeImage img = white.getPixels();
            if (img != null) img.fillRect(0, 0, 16, 16, 0xFFFFFFFF);
            white.upload();
        }
        return white.getId();
    }

    public static void beginNoFog() {
        savedFogStart = RenderSystem.getShaderFogStart();
        savedFogEnd = RenderSystem.getShaderFogEnd();
        savedFogShape = RenderSystem.getShaderFogShape();
        FogRenderer.setupNoFog();
    }

    public static void endNoFog() {
        RenderSystem.setShaderFogStart(savedFogStart);
        RenderSystem.setShaderFogEnd(savedFogEnd);
        RenderSystem.setShaderFogShape(savedFogShape);
    }

    /** 레이어 상태를 켠다. 그 뒤 {@link #draw} 를 여러 번, 끝나면 {@link #endLayer}. */
    public static void beginLayer(RenderType type, boolean flatLight) {
        type.setupRenderState();
        if (flatLight) RenderSystem.setShaderTexture(2, whiteLightmap());
    }

    public static void endLayer(RenderType type) {
        VertexBuffer.unbind();
        type.clearRenderState();
    }

    public static void draw(VertexBuffer buffer, Matrix4f modelView, Matrix4f projection) {
        ShaderInstance shader = com.mojang.blaze3d.systems.RenderSystem.getShader();
        if (shader == null || buffer == null) return;
        buffer.bind();
        buffer.drawWithShader(modelView, projection, shader);
    }
}
