package ru.shulkerhl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.FramePassManager;
import net.minecraftforge.client.event.AddFramePassEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

@Mod(ShulkerHighlight.MODID)
public final class ShulkerHighlight {

    public static final String MODID = "shulkerhighlight";

    public ShulkerHighlight(FMLJavaModLoadingContext context) {
        var modBusGroup = context.getModBusGroup();
        FMLClientSetupEvent.getBus(modBusGroup).addListener(ShulkerHighlight::onClientSetup);
        AddFramePassEvent.BUS.addListener(ShulkerHighlight::onAddFramePass);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> { Cfg.load(); RT.init(); });
    }

    private static void onAddFramePass(AddFramePassEvent event) {
        event.addPass(Identifier.fromNamespaceAndPath(MODID, "highlight"), new HighlightPass());
    }

    // ---------- регистрация клавиши ----------
    @Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static final class ModEvents {
        @SubscribeEvent
        public static void onKeys(RegisterKeyMappingsEvent event) {
            event.register(Keys.MENU);
        }
    }

    // ---------- проход рендера (замена RenderLevelStageEvent) ----------
    static final class HighlightPass implements FramePassManager.PassDefinition {

        @Override
        public void extracts(LevelTargetBundle bundle, FramePass pass, DeltaTracker delta) {
            // обязательная привязка хотя бы к одному таргету, иначе упадёт resolvePassOrder
            bundle.main = pass.readsAndWrites(bundle.main);

            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return;
            Keys.poll(mc);
            Scan.tick(mc.level);
        }

        @Override
        public void executes(LevelRenderState state) {
            Draw.render();
        }
    }

    // ==================================================================
    static final class Keys {
        static final KeyMapping MENU = new KeyMapping(
                "key.shulkerhighlight.menu", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_H, "key.categories.shulkerhighlight");

        static void poll(Minecraft mc) {
            boolean free = mc.screen == null;
            while (MENU.consumeClick()) {
                if (free) mc.setScreen(new Menu());
            }
        }
    }

    // ==================================================================
    //  КОНФИГ
    // ==================================================================
    public static final class Cfg {
        public enum Mode { OUTLINE, FILL, BOTH }
        public enum Src { SHULKER, CUSTOM }

        public boolean enabled = true;
        public Mode mode = Mode.BOTH;
        public Src colorSource = Src.SHULKER;
        public int customRgb = 0x33FF99;
        public int fillAlpha = 30;
        public int outlineAlpha = 90;
        public int lineWidth = 3;
        public boolean throughWalls = true;
        public int range = 48;
        public boolean sneakOnly = false;

        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private static Cfg inst = new Cfg();
        private static Path file;

        public static Cfg get() { return inst; }

        static void load() {
            try {
                file = Minecraft.getInstance().gameDirectory.toPath()
                        .resolve("config").resolve("shulkerhighlight.json");
                if (Files.exists(file)) {
                    Cfg c = GSON.fromJson(Files.readString(file), Cfg.class);
                    if (c != null) inst = c;
                }
                save();
            } catch (Exception ex) { inst = new Cfg(); }
        }

        public static void save() {
            try {
                if (file == null) return;
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(inst));
            } catch (Exception ignored) {}
        }

        static void reset() { inst = new Cfg(); save(); }
    }

    // ==================================================================
    //  ЦВЕТА КРАСИТЕЛЕЙ
    // ==================================================================
    static final Map<DyeColor, Integer> DYE = new EnumMap<>(DyeColor.class);
    static final int UNDYED = 0x9A6A9A;
    static {
        DYE.put(DyeColor.WHITE, 0xF9FFFE);      DYE.put(DyeColor.ORANGE, 0xF9801D);
        DYE.put(DyeColor.MAGENTA, 0xC74EBD);    DYE.put(DyeColor.LIGHT_BLUE, 0x3AB3DA);
        DYE.put(DyeColor.YELLOW, 0xFED83D);     DYE.put(DyeColor.LIME, 0x80C71F);
        DYE.put(DyeColor.PINK, 0xF38BAA);       DYE.put(DyeColor.GRAY, 0x474F52);
        DYE.put(DyeColor.LIGHT_GRAY, 0x9D9D97); DYE.put(DyeColor.CYAN, 0x169C9C);
        DYE.put(DyeColor.PURPLE, 0x8932B8);     DYE.put(DyeColor.BLUE, 0x3C44AA);
        DYE.put(DyeColor.BROWN, 0x835432);      DYE.put(DyeColor.GREEN, 0x5E7C16);
        DYE.put(DyeColor.RED, 0xB02E26);        DYE.put(DyeColor.BLACK, 0x1D1D21);
    }

    // ==================================================================
    //  ПОИСК ПОСТАВЛЕННЫХ ШАЛКЕРОВ
    // ==================================================================
    static final class Scan {
        record Target(BlockPos pos, int rgb) {}

        private static final List<Target> LIST = new ArrayList<>();
        private static long lastMs = 0;
        private static ChunkPos lastChunk;

        static List<Target> list() { return LIST; }

        static void tick(ClientLevel level) {
            Cfg c = Cfg.get();
            if (!c.enabled) { LIST.clear(); return; }

            Minecraft mc = Minecraft.getInstance();
            BlockPos o = mc.player.blockPosition();
            ChunkPos cur = new ChunkPos(o);
            long now = System.currentTimeMillis();
            if (now - lastMs < 500 && cur.equals(lastChunk)) return;
            lastMs = now; lastChunk = cur;

            int r = c.range, r2 = r * r;
            List<Target> out = new ArrayList<>();
            var src = level.getChunkSource();

            for (int cx = (o.getX() - r) >> 4; cx <= (o.getX() + r) >> 4; cx++)
                for (int cz = (o.getZ() - r) >> 4; cz <= (o.getZ() + r) >> 4; cz++) {
                    LevelChunk ch = src.getChunkNow(cx, cz);
                    if (ch == null) continue;
                    for (BlockEntity be : ch.getBlockEntities().values()) {
                        if (!(be instanceof ShulkerBoxBlockEntity sh)) continue;
                        BlockPos bp = be.getBlockPos();
                        if (bp.distSqr(o) > r2) continue;
                        DyeColor dc = sh.getColor();
                        out.add(new Target(bp.immutable(),
                                dc == null ? UNDYED : DYE.getOrDefault(dc, UNDYED)));
                    }
                }
            LIST.clear(); LIST.addAll(out);
        }
    }

    // ==================================================================
    //  ТИПЫ РЕНДЕРА — оба свои, RenderTypes не используется
    // ==================================================================
    static final class RT {
        private static RenderType xray;
        private static RenderType normal;

        static void init() {
            xray   = make("xray_box",   DepthTestFunction.NO_DEPTH_TEST);
            normal = make("normal_box", DepthTestFunction.LEQUAL_DEPTH_TEST);
        }

        private static RenderType make(String name, DepthTestFunction depth) {
            try {
                RenderPipeline pipe = RenderPipeline
                        .builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath(MODID, name))
                        .withDepthTestFunction(depth)
                        .withDepthWrite(false)
                        .withCull(true)
                        .build();

                RenderSetup setup = RenderSetup.builder(pipe)
                        .bufferSize(1536)
                        .createRenderSetup();

                return RenderType.create(MODID + ":" + name, setup);
            } catch (Throwable t) {
                System.err.println("[ShulkerHighlight] Не создан тип " + name + ": " + t);
                return null;
            }
        }

        static RenderType get(boolean throughWalls) {
            if (throughWalls && xray != null) return xray;
            return normal;
        }
    }

    // ==================================================================
    //  ОТРИСОВКА
    // ==================================================================
    static final class Draw {
        static void render() {
            Cfg c = Cfg.get();
            Minecraft mc = Minecraft.getInstance();
            if (!c.enabled || mc.player == null || mc.level == null) return;
            if (c.sneakOnly && !mc.player.isShiftKeyDown()) return;
            if (Scan.list().isEmpty()) return;

            RenderType type = RT.get(c.throughWalls);
            if (type == null) return;

            Camera camera = mc.gameRenderer.getMainCamera();
            Vec3 cam = camera.getPosition();
            double r2 = (double) c.range * c.range;

            MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
            VertexConsumer vc = buf.getBuffer(type);
            float fa = c.fillAlpha / 100f, la = c.outlineAlpha / 100f;

            PoseStack pose = new PoseStack();
            pose.pushPose();
            pose.translate(-cam.x, -cam.y, -cam.z);

            for (Scan.Target t : Scan.list()) {
                BlockPos p = t.pos();
                double d2 = cam.distanceToSqr(p.getX() + .5, p.getY() + .5, p.getZ() + .5);
                if (d2 > r2) continue;

                int rgb = c.colorSource == Cfg.Src.CUSTOM ? c.customRgb : t.rgb();
                float r = ((rgb >> 16) & 255) / 255f;
                float g = ((rgb >> 8) & 255) / 255f;
                float b = (rgb & 255) / 255f;

                double e = .002;
                double x0 = p.getX() + e,     y0 = p.getY() + e,     z0 = p.getZ() + e;
                double x1 = p.getX() + 1 - e, y1 = p.getY() + 1 - e, z1 = p.getZ() + 1 - e;

                if (c.mode != Cfg.Mode.OUTLINE && fa > .001f)
                    box(pose, vc, x0, y0, z0, x1, y1, z1, r, g, b, fa);

                if (c.mode != Cfg.Mode.FILL && la > .001f) {
                    double th = Mth.clamp(c.lineWidth * .0022 * Math.sqrt(d2), .006, .25);
                    outline(pose, vc, x0, y0, z0, x1, y1, z1, th, r, g, b, la);
                }
            }

            pose.popPose();
            buf.endBatch(type);
        }

        static void outline(PoseStack ps, VertexConsumer vc, double x0, double y0, double z0,
                            double x1, double y1, double z1, double th,
                            float r, float g, float b, float a) {
            double h = th / 2;
            double[] xs = {x0, x1}, ys = {y0, y1}, zs = {z0, z1};
            for (double y : ys) for (double z : zs) box(ps, vc, x0-h, y-h, z-h, x1+h, y+h, z+h, r,g,b,a);
            for (double x : xs) for (double z : zs) box(ps, vc, x-h, y0+h, z-h, x+h, y1-h, z+h, r,g,b,a);
            for (double x : xs) for (double y : ys) box(ps, vc, x-h, y-h, z0+h, x+h, y+h, z1-h, r,g,b,a);
        }

        static void box(PoseStack ps, VertexConsumer vc, double x0, double y0, double z0,
                        double x1, double y1, double z1, float r, float g, float b, float a) {
            PoseStack.Pose p = ps.last();
            q(p,vc,x0,y0,z0, x1,y0,z0, x1,y0,z1, x0,y0,z1, r,g,b,a);
            q(p,vc,x0,y1,z1, x1,y1,z1, x1,y1,z0, x0,y1,z0, r,g,b,a);
            q(p,vc,x0,y0,z0, x0,y1,z0, x1,y1,z0, x1,y0,z0, r,g,b,a);
            q(p,vc,x1,y0,z1, x1,y1,z1, x0,y1,z1, x0,y0,z1, r,g,b,a);
            q(p,vc,x0,y0,z1, x0,y1,z1, x0,y1,z0, x0,y0,z0, r,g,b,a);
            q(p,vc,x1,y0,z0, x1,y1,z0, x1,y1,z1, x1,y0,z1, r,g,b,a);
        }

        static void q(PoseStack.Pose p, VertexConsumer vc,
                      double ax,double ay,double az, double bx,double by,double bz,
                      double cx,double cy,double cz, double dx,double dy,double dz,
                      float r,float g,float b,float a) {
            vc.addVertex(p,(float)ax,(float)ay,(float)az).setColor(r,g,b,a);
            vc.addVertex(p,(float)bx,(float)by,(float)bz).setColor(r,g,b,a);
            vc.addVertex(p,(float)cx,(float)cy,(float)cz).setColor(r,g,b,a);
            vc.addVertex(p,(float)dx,(float)dy,(float)dz).setColor(r,g,b,a);
        }
    }

    // ==================================================================
    //  МЕНЮ
    // ==================================================================
    public static final class Menu extends Screen {
        private static final int W = 324, H = 208, CW = 150, RH = 22;
        private int lx, ty;

        public Menu() { super(Component.translatable("shulkerhighlight.screen.title")); }

        static Component tr(String k) { return Component.translatable("shulkerhighlight.opt." + k); }

        @Override protected void init() {
            Cfg c = Cfg.get();
            lx = (width - W) / 2; ty = (height - H) / 2;
            int x1 = lx + 8, x2 = lx + W - 8 - CW, yL = ty + 24, yR = ty + 24;

            addRenderableWidget(bool(x1, yL, "enabled", c.enabled, v -> c.enabled = v)); yL += RH;

            addRenderableWidget(CycleButton.builder((Cfg.Mode m) ->
                    Component.translatable("shulkerhighlight.mode." + m.name().toLowerCase()))
                    .withValues(List.of(Cfg.Mode.values()))
                    .create(x1, yL, CW, 20, tr("mode"), c.mode,
                            (b, v) -> { c.mode = v; Cfg.save(); })); yL += RH;

            addRenderableWidget(CycleButton.builder((Cfg.Src s) ->
                    Component.translatable("shulkerhighlight.source." + s.name().toLowerCase()))
                    .withValues(List.of(Cfg.Src.values()))
                    .create(x1, yL, CW, 20, tr("colorSource"), c.colorSource,
                            (b, v) -> { c.colorSource = v; Cfg.save(); })); yL += RH;

            addRenderableWidget(sl(x1, yL, "r", 0, 255, (c.customRgb>>16)&255,
                    v -> c.customRgb = (c.customRgb & 0x00FFFF) | (v<<16))); yL += RH;
            addRenderableWidget(sl(x1, yL, "g", 0, 255, (c.customRgb>>8)&255,
                    v -> c.customRgb = (c.customRgb & 0xFF00FF) | (v<<8))); yL += RH;
            addRenderableWidget(sl(x1, yL, "b", 0, 255, c.customRgb&255,
                    v -> c.customRgb = (c.customRgb & 0xFFFF00) | v));

            addRenderableWidget(sl(x2, yR, "fillAlpha", 0, 100, c.fillAlpha, v -> c.fillAlpha = v)); yR += RH;
            addRenderableWidget(sl(x2, yR, "outlineAlpha", 0, 100, c.outlineAlpha, v -> c.outlineAlpha = v)); yR += RH;
            addRenderableWidget(sl(x2, yR, "lineWidth", 1, 8, c.lineWidth, v -> c.lineWidth = v)); yR += RH;
            addRenderableWidget(sl(x2, yR, "range", 8, 128, c.range, v -> c.range = v)); yR += RH;
            addRenderableWidget(bool(x2, yR, "throughWalls", c.throughWalls, v -> c.throughWalls = v)); yR += RH;
            addRenderableWidget(bool(x2, yR, "sneakOnly", c.sneakOnly, v -> c.sneakOnly = v));

            int by = ty + H - 28;
            addRenderableWidget(Button.builder(tr("reset"),
                    b -> { Cfg.reset(); minecraft.setScreen(new Menu()); })
                    .bounds(lx + 8, by, 100, 20).build());
            addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                    .bounds(lx + W - 108, by, 100, 20).build());
        }

        CycleButton<Boolean> bool(int x, int y, String k, boolean v, Consumer<Boolean> set) {
            return CycleButton.booleanBuilder(CommonComponents.OPTION_ON, CommonComponents.OPTION_OFF)
                    .create(x, y, CW, 20, tr(k), v, (b, nv) -> { set.accept(nv); Cfg.save(); });
        }

        AbstractSliderButton sl(int x, int y, String k, int min, int max, int cur, IntConsumer set) {
            return new AbstractSliderButton(x, y, CW, 20, Component.empty(),
                    max == min ? 0 : (double)(cur - min) / (max - min)) {
                int val() { return min + (int) Math.round(value * (max - min)); }
                @Override protected void updateMessage() { setMessage(tr(k).copy().append(": " + val())); }
                @Override protected void applyValue() { set.accept(val()); Cfg.save(); }
            };
        }

        @Override public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
            g.fill(0, 0, width, height, 0xA0000000);
            g.fill(lx - 1, ty - 1, lx + W + 1, ty + H + 1, 0xFF6A6A8A);
            g.fill(lx, ty, lx + W, ty + H, 0xF0141420);
        }

        @Override public void render(GuiGraphics g, int mx, int my, float pt) {
            super.render(g, mx, my, pt);
            g.drawCenteredString(font, title, width / 2, ty + 8, 0xFFFFFF);
            int px = lx + 8 + CW + 4, py = ty + 24 + RH * 3;
            g.fill(px, py, px + 6, py + RH * 3 - 2, 0xFF000000 | Cfg.get().customRgb);
        }

        @Override public boolean isPauseScreen() { return false; }
    }
}
