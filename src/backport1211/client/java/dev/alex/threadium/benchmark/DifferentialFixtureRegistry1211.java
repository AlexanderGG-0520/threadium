package dev.alex.threadium.benchmark;

import dev.alex.threadium.render.modelpart.material.RenderLayer1211Descriptor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.util.Identifier;

/** Semantic Vanilla 1.21.1 ModelPart fixtures used only by the differential harness. */
public final class DifferentialFixtureRegistry1211 {
    public enum Phase {
        BASELINE,
        A,
        B,
        C
    }

    public enum TargetContract {
        COLOR_DEPTH,
        OUTLINE,
        DEPTH_ONLY
    }

    public enum OutputTarget {
        MAIN,
        OUTLINE,
        ITEM_ENTITY
    }

    public record Fixture(
            String canonical,
            Phase phase,
            String fixtureName,
            EntityModelLayer modelLayer,
            Identifier texture,
            RenderLayer1211Descriptor.Kind kind,
            boolean sorted,
            List<Float> animationSamples,
            List<Float> cameraYawSamples,
            TargetContract targetContract,
            OutputTarget outputTarget,
            int colorChannelTolerance,
            float depthTolerance) {}

    private static final Identifier PLAYER = id("textures/entity/player/wide/steve.png");
    private static final Identifier BAT = id("textures/entity/bat.png");
    private static final Identifier COW = id("textures/entity/cow/cow.png");
    private static final Identifier SPIDER = id("textures/entity/spider/spider.png");
    private static final Identifier SPIDER_EYES = id("textures/entity/spider_eyes.png");
    private static final Identifier CHEST = id("textures/entity/chest/normal.png");
    private static final Identifier DESTROY = id("textures/block/destroy_stage_3.png");
    private static final Identifier BREEZE = id("textures/entity/breeze/breeze_wind.png");
    private static final Identifier CREEPER_ARMOR = id("textures/entity/creeper/creeper_armor.png");
    private static final LinkedHashMap<String, Fixture> FIXTURES = build();

    private DifferentialFixtureRegistry1211() {}

    private static LinkedHashMap<String, Fixture> build() {
        LinkedHashMap<String, Fixture> fixtures = new LinkedHashMap<>();
        add(
                fixtures,
                RenderLayer1211Descriptor.Kind.ENTITY_SOLID,
                Phase.BASELINE,
                "Wide player opaque body",
                EntityModelLayers.PLAYER,
                PLAYER,
                false,
                times(0),
                cameras(0),
                TargetContract.COLOR_DEPTH,
                OutputTarget.MAIN);

        add(
                fixtures,
                RenderLayer1211Descriptor.Kind.ENTITY_CUTOUT_CULL,
                Phase.A,
                "Bat cutout with back-face culling",
                EntityModelLayers.BAT,
                BAT,
                false,
                times(0),
                cameras(0, 180),
                TargetContract.COLOR_DEPTH,
                OutputTarget.MAIN);
        add(
                fixtures,
                RenderLayer1211Descriptor.Kind.ENTITY_CUTOUT,
                Phase.A,
                "Cow no-cull cutout",
                EntityModelLayers.COW,
                COW,
                false,
                times(0),
                cameras(0, 180),
                TargetContract.COLOR_DEPTH,
                OutputTarget.MAIN);
        add(
                fixtures,
                RenderLayer1211Descriptor.Kind.ENTITY_CUTOUT_Z_OFFSET,
                Phase.A,
                "Shulker cutout z offset",
                EntityModelLayers.SHULKER,
                id("textures/entity/shulker/shulker.png"),
                false,
                times(0),
                cameras(0),
                TargetContract.COLOR_DEPTH,
                OutputTarget.MAIN);
        add(
                fixtures,
                RenderLayer1211Descriptor.Kind.ARMOR_CUTOUT,
                Phase.A,
                "Humanoid armor cutout",
                EntityModelLayers.PLAYER_OUTER_ARMOR,
                id("textures/models/armor/iron_layer_1.png"),
                false,
                times(0),
                cameras(0),
                TargetContract.COLOR_DEPTH,
                OutputTarget.MAIN);
        add(
                fixtures,
                RenderLayer1211Descriptor.Kind.ARMOR_DECAL,
                Phase.A,
                "Humanoid armor equal-depth decal",
                EntityModelLayers.PLAYER_OUTER_ARMOR,
                id("textures/models/armor/iron_layer_1.png"),
                false,
                times(0),
                cameras(0),
                TargetContract.COLOR_DEPTH,
                OutputTarget.MAIN);
        add(
                fixtures,
                RenderLayer1211Descriptor.Kind.ENTITY_DECAL,
                Phase.A,
                "Cow equal-depth entity decal",
                EntityModelLayers.COW,
                COW,
                false,
                times(0),
                cameras(0),
                TargetContract.COLOR_DEPTH,
                OutputTarget.MAIN);

        add(
                fixtures,
                RenderLayer1211Descriptor.Kind.ENTITY_TRANSLUCENT,
                Phase.B,
                "Allay translucent body",
                EntityModelLayers.ALLAY,
                id("textures/entity/allay/allay.png"),
                true,
                times(0),
                cameras(0, 180),
                TargetContract.COLOR_DEPTH,
                OutputTarget.MAIN);
        add(
                fixtures,
                RenderLayer1211Descriptor.Kind.ENTITY_TRANSLUCENT_CULL,
                Phase.B,
                "Culled translucent cow",
                EntityModelLayers.COW,
                COW,
                true,
                times(0),
                cameras(0, 180),
                TargetContract.COLOR_DEPTH,
                OutputTarget.MAIN);
        add(
                fixtures,
                RenderLayer1211Descriptor.Kind.ENTITY_TRANSLUCENT_EMISSIVE,
                Phase.B,
                "Emissive translucent spider",
                EntityModelLayers.SPIDER,
                SPIDER_EYES,
                true,
                times(0),
                cameras(0, 180),
                TargetContract.COLOR_DEPTH,
                OutputTarget.MAIN);
        add(
                fixtures,
                RenderLayer1211Descriptor.Kind.ITEM_ENTITY_TRANSLUCENT_CULL,
                Phase.B,
                "Item-target translucent cow",
                EntityModelLayers.COW,
                COW,
                true,
                times(0),
                cameras(0, 180),
                TargetContract.COLOR_DEPTH,
                OutputTarget.ITEM_ENTITY);
        add(
                fixtures,
                RenderLayer1211Descriptor.Kind.BANNER_PATTERN,
                Phase.B,
                "Banner no-outline translucent layer",
                EntityModelLayers.BANNER,
                id("textures/entity/banner/base.png"),
                true,
                times(0),
                cameras(0, 180),
                TargetContract.COLOR_DEPTH,
                OutputTarget.MAIN);
        add(
                fixtures,
                RenderLayer1211Descriptor.Kind.EYES,
                Phase.B,
                "Spider emissive eyes",
                EntityModelLayers.SPIDER,
                SPIDER_EYES,
                true,
                times(0),
                cameras(0, 180),
                TargetContract.COLOR_DEPTH,
                OutputTarget.MAIN);
        add(
                fixtures,
                RenderLayer1211Descriptor.Kind.CRUMBLING,
                Phase.B,
                "Chest destroy-stage sheeted decal",
                EntityModelLayers.CHEST,
                DESTROY,
                true,
                times(0),
                cameras(0, 90),
                TargetContract.COLOR_DEPTH,
                OutputTarget.MAIN);

        add(
                fixtures,
                RenderLayer1211Descriptor.Kind.BREEZE_WIND,
                Phase.C,
                "Breeze wind animated texturing",
                EntityModelLayers.BREEZE_WIND,
                BREEZE,
                true,
                times(0, .25f, .5f, .75f),
                cameras(0, 180),
                TargetContract.COLOR_DEPTH,
                OutputTarget.MAIN);
        add(
                fixtures,
                RenderLayer1211Descriptor.Kind.ENERGY_SWIRL,
                Phase.C,
                "Creeper energy swirl",
                EntityModelLayers.CREEPER_ARMOR,
                CREEPER_ARMOR,
                true,
                times(0, .25f, .5f, .75f),
                cameras(0, 180),
                TargetContract.COLOR_DEPTH,
                OutputTarget.MAIN);
        add(
                fixtures,
                RenderLayer1211Descriptor.Kind.GLINT,
                Phase.C,
                "Humanoid armor entity glint",
                EntityModelLayers.PLAYER_OUTER_ARMOR,
                null,
                false,
                times(0, .25f, .5f, .75f),
                cameras(0),
                TargetContract.COLOR_DEPTH,
                OutputTarget.MAIN);
        add(
                fixtures,
                RenderLayer1211Descriptor.Kind.OUTLINE_CULL,
                Phase.C,
                "Bat culling outline",
                EntityModelLayers.BAT,
                BAT,
                false,
                times(0),
                cameras(0, 180),
                TargetContract.OUTLINE,
                OutputTarget.OUTLINE);
        add(
                fixtures,
                RenderLayer1211Descriptor.Kind.OUTLINE_NO_CULL,
                Phase.C,
                "Cow no-cull outline",
                EntityModelLayers.COW,
                COW,
                false,
                times(0),
                cameras(0, 180),
                TargetContract.OUTLINE,
                OutputTarget.OUTLINE);
        add(
                fixtures,
                RenderLayer1211Descriptor.Kind.WATER_MASK,
                Phase.C,
                "Depth-only water mask geometry",
                EntityModelLayers.CHEST,
                null,
                false,
                times(0),
                cameras(0),
                TargetContract.DEPTH_ONLY,
                OutputTarget.MAIN);
        return fixtures;
    }

    private static void add(
            Map<String, Fixture> fixtures,
            RenderLayer1211Descriptor.Kind kind,
            Phase phase,
            String name,
            EntityModelLayer layer,
            Identifier texture,
            boolean sorted,
            List<Float> times,
            List<Float> cameras,
            TargetContract targets,
            OutputTarget outputTarget) {
        String canonical = kind.name().toLowerCase(java.util.Locale.ROOT);
        int tolerance =
                switch (kind) {
                    case BANNER_PATTERN -> 2;
                    case EYES,
                            GLINT,
                            ENTITY_CUTOUT,
                            ENTITY_CUTOUT_Z_OFFSET,
                            ENTITY_TRANSLUCENT,
                            ENTITY_TRANSLUCENT_EMISSIVE,
                            ARMOR_CUTOUT,
                            ARMOR_DECAL -> 1;
                    default -> 0;
                };
        Fixture previous = fixtures.put(
                canonical,
                new Fixture(
                        canonical,
                        phase,
                        name,
                        layer,
                        texture,
                        kind,
                        sorted,
                        List.copyOf(times),
                        List.copyOf(cameras),
                        targets,
                        outputTarget,
                        tolerance,
                        sorted ? 1.0e-7f : 0.0f));
        if (previous != null) throw new IllegalStateException("Duplicate differential fixture: " + canonical);
    }

    public static List<Fixture> fixtures() {
        return List.copyOf(FIXTURES.values());
    }

    public static Fixture require(String canonical) {
        Fixture fixture = FIXTURES.get(canonical);
        if (fixture == null) throw new IllegalArgumentException("Unknown canonical pipeline: " + canonical);
        return fixture;
    }

    public static List<Fixture> phase(Phase phase) {
        return FIXTURES.values().stream()
                .filter(fixture -> fixture.phase() == phase)
                .toList();
    }

    public static boolean contains(String canonical) {
        return FIXTURES.containsKey(canonical);
    }

    private static Identifier id(String path) {
        return Identifier.ofVanilla(path);
    }

    private static List<Float> times(float... values) {
        return boxed(values);
    }

    private static List<Float> cameras(float... values) {
        return boxed(values);
    }

    private static List<Float> boxed(float[] values) {
        java.util.ArrayList<Float> result = new java.util.ArrayList<>(values.length);
        for (float value : values) result.add(value);
        return List.copyOf(result);
    }
}
