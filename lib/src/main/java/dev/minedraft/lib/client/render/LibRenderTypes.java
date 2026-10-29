package dev.minedraft.lib.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

import java.util.OptionalDouble;

/**
 * 편집창·투영 전용 렌더 타입. 모두 주 화면 버퍼에 그린다
 * (바닐라 반투명·선 타입은 "화려함" 그래픽에서 다른 버퍼로 가서 편집창에 안 보일 수 있다).
 */
public final class LibRenderTypes extends RenderStateShard {
    private LibRenderTypes() {
        super(null, null, null);
    }

    /** 반투명 블록 (물, 유리, 유령 블록). */
    public static final RenderType TRANSLUCENT = RenderType.create("minedraftlib_translucent", DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS, 262144, true, true,
            RenderType.CompositeState.builder()
                    .setLightmapState(LIGHTMAP)
                    .setShaderState(RENDERTYPE_TRANSLUCENT_SHADER)
                    .setTextureState(BLOCK_SHEET_MIPPED)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .createCompositeState(true));

    /** 유령 블록: 깊이를 쓰지 않아 뒤의 유령 블록도 비친다. */
    public static final RenderType GHOST = RenderType.create("minedraftlib_ghost", DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS, 262144, true, true,
            RenderType.CompositeState.builder()
                    .setLightmapState(LIGHTMAP)
                    .setShaderState(RENDERTYPE_TRANSLUCENT_SHADER)
                    .setTextureState(BLOCK_SHEET_MIPPED)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(true));

    public static final RenderType LINES = RenderType.create("minedraftlib_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES, 4096, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(2.0)))
                    .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .createCompositeState(false));

    public static final RenderType LINES_SEE_THROUGH = RenderType.create("minedraftlib_lines_see_through",
            DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 4096, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(1.5)))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .setDepthTestState(NO_DEPTH_TEST)
                    .createCompositeState(false));

    /** 편집창 블록 테두리. 깊이 보정은 그릴 때 뷰 행렬로 한다 ({@code EditorRenderer#lineView}). */
    public static final RenderType OUTLINE_LINES = RenderType.create("minedraftlib_outline_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES, 4096, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(1.0)))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .createCompositeState(false));

    /** 색 상자 (철거 필터, 태그 표시, 반짝임). */
    public static final RenderType QUADS = RenderType.create("minedraftlib_quads", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 65536, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER)
                    .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .createCompositeState(false));

    public static final RenderType QUADS_SEE_THROUGH = RenderType.create("minedraftlib_quads_see_through",
            DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 65536, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .setDepthTestState(NO_DEPTH_TEST)
                    .createCompositeState(false));
}
