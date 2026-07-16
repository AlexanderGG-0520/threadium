package dev.alex.threadium.benchmark;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Semantic Vanilla ModelPart fixtures used only by the differential harness. */
public final class DifferentialFixtureRegistry {
    public enum Phase { BASELINE, A, B, C }
    public enum TargetContract { COLOR_DEPTH, OUTLINE, DEPTH_ONLY }
    public enum Material {
        ENTITY_SOLID, ENTITY_CUTOUT_CULL, ENTITY_CUTOUT, ENTITY_CUTOUT_Z_OFFSET,
        ENTITY_CUTOUT_DISSOLVE, ENTITY_TRANSLUCENT, ENTITY_TRANSLUCENT_CULL,
        ENTITY_TRANSLUCENT_EMISSIVE, ARMOR_CUTOUT, ARMOR_DECAL, ARMOR_TRANSLUCENT,
        BANNER_PATTERN, BREEZE_WIND, ENERGY_SWIRL, EYES, GLINT, OUTLINE_CULL,
        OUTLINE_NO_CULL, CRUMBLING, WATER_MASK
    }

    public record Fixture(
            String canonical,
            Phase phase,
            String fixtureName,
            ModelLayerLocation modelLayer,
            Identifier texture,
            List<Identifier> auxiliaryResources,
            Material material,
            boolean sorted,
            List<Float> animationSamples,
            List<Float> cameraYawSamples,
            TargetContract targetContract,
            int colorChannelTolerance,
            float depthTolerance
    ) {}

    private static final Identifier STEVE = id("textures/entity/player/wide/steve.png");
    private static final Identifier DRAGON = id("textures/entity/enderdragon/dragon.png");
    private static final Identifier DRAGON_DISSOLVE = id("textures/entity/enderdragon/dragon_exploding.png");
    private static final Identifier ARMOR_TRIM_ATLAS = id("textures/atlas/armor_trims.png");
    private static final Identifier BANNER_ATLAS = id("textures/atlas/banner_patterns.png");
    private static final LinkedHashMap<String, Fixture> FIXTURES = build();

    private DifferentialFixtureRegistry() {}

    private static LinkedHashMap<String, Fixture> build() {
        LinkedHashMap<String, Fixture> fixtures = new LinkedHashMap<>();
        add(fixtures, "entity_solid", Phase.BASELINE, "Vanilla wide-player opaque body", ModelLayers.PLAYER, STEVE, Material.ENTITY_SOLID, false, times(0), cameras(0), TargetContract.COLOR_DEPTH);

        add(fixtures, "entity_cutout_cull", Phase.A, "Bat cutout with back-face culling", ModelLayers.BAT, id("textures/entity/bat/bat.png"), Material.ENTITY_CUTOUT_CULL, false, times(0), cameras(0, 180), TargetContract.COLOR_DEPTH);
        add(fixtures, "entity_cutout", Phase.A, "Temperate cow ordinary living model", ModelLayers.COW, id("textures/entity/cow/cow_temperate.png"), Material.ENTITY_CUTOUT, false, times(0), cameras(0, 180), TargetContract.COLOR_DEPTH);
        add(fixtures, "entity_cutout_z_offset", Phase.A, "Shulker view-offset shell", ModelLayers.SHULKER, id("textures/entity/shulker/shulker.png"), Material.ENTITY_CUTOUT_Z_OFFSET, false, times(0), cameras(0), TargetContract.COLOR_DEPTH);
        add(fixtures, "armor_cutout", Phase.A, "Iron humanoid chest armor", ModelLayers.PLAYER_ARMOR.chest(), id("textures/entity/equipment/humanoid/iron.png"), Material.ARMOR_CUTOUT, false, times(0), cameras(0), TargetContract.COLOR_DEPTH);
        add(fixtures, "armor_decal", Phase.A, "Sentry iron armor trim decal", ModelLayers.PLAYER_ARMOR.chest(), ARMOR_TRIM_ATLAS, List.of(id("textures/entity/equipment/humanoid/iron.png"),id("textures/trims/entity/humanoid/sentry.png")), Material.ARMOR_DECAL, false, times(0), cameras(0), TargetContract.COLOR_DEPTH);

        add(fixtures, "entity_translucent", Phase.B, "Allay translucent body", ModelLayers.ALLAY, id("textures/entity/allay/allay.png"), Material.ENTITY_TRANSLUCENT, true, times(0), cameras(0, 180), TargetContract.COLOR_DEPTH);
        add(fixtures, "entity_translucent_cull", Phase.B, "Culled translucent slime shell", ModelLayers.SLIME_OUTER, id("textures/entity/slime/slime.png"), Material.ENTITY_TRANSLUCENT_CULL, true, times(0), cameras(0, 180), TargetContract.COLOR_DEPTH);
        add(fixtures, "entity_translucent_emissive", Phase.B, "Warden bioluminescent mask", ModelLayers.WARDEN_BIOLUMINESCENT, id("textures/entity/warden/warden_bioluminescent_layer.png"), Material.ENTITY_TRANSLUCENT_EMISSIVE, true, times(0), cameras(0, 180), TargetContract.COLOR_DEPTH);
        add(fixtures, "armor_translucent", Phase.B, "Damaged wolf armor body", ModelLayers.WOLF_ARMOR, id("textures/entity/equipment/wolf_body/armadillo_scute.png"), Material.ARMOR_TRANSLUCENT, true, times(0), cameras(0, 180), TargetContract.COLOR_DEPTH);
        add(fixtures, "banner_pattern", Phase.B, "Standing banner base-pattern atlas sprite", ModelLayers.STANDING_BANNER_FLAG, BANNER_ATLAS, List.of(id("textures/entity/banner/base.png")), Material.BANNER_PATTERN, true, times(0), cameras(0, 180), TargetContract.COLOR_DEPTH);
        add(fixtures, "eyes", Phase.B, "Spider emissive eyes mask", ModelLayers.SPIDER, id("textures/entity/spider/spider_eyes.png"), Material.EYES, true, times(0), cameras(0, 180), TargetContract.COLOR_DEPTH);
        add(fixtures, "crumbling", Phase.B, "Chest destroy-stage sheeted decal", ModelLayers.CHEST, id("textures/block/destroy_stage_3.png"), Material.CRUMBLING, true, times(0), cameras(0, 90), TargetContract.COLOR_DEPTH);

        add(fixtures, "entity_cutout_dissolve", Phase.C, "Ender Dragon dissolve mask", ModelLayers.ENDER_DRAGON, DRAGON, List.of(DRAGON_DISSOLVE), Material.ENTITY_CUTOUT_DISSOLVE, false, times(0, .5f, .9f), cameras(0), TargetContract.COLOR_DEPTH);
        add(fixtures, "breeze_wind", Phase.C, "Breeze wind animated layer", ModelLayers.BREEZE_WIND, id("textures/entity/breeze/breeze_wind.png"), Material.BREEZE_WIND, true, times(0, .25f, .5f, .75f), cameras(0, 180), TargetContract.COLOR_DEPTH);
        add(fixtures, "energy_swirl", Phase.C, "Charged Creeper animated armor", ModelLayers.CREEPER_ARMOR, id("textures/entity/creeper/creeper_armor.png"), Material.ENERGY_SWIRL, true, times(0, .25f, .5f, .75f), cameras(0, 180), TargetContract.COLOR_DEPTH);
        add(fixtures, "glint", Phase.C, "Humanoid armor entity glint", ModelLayers.PLAYER_ARMOR.chest(), id("textures/misc/enchanted_glint_armor.png"), Material.GLINT, false, times(0, .25f, .5f, .75f), cameras(0), TargetContract.COLOR_DEPTH);
        add(fixtures, "outline_cull", Phase.C, "Bat culling outline", ModelLayers.BAT, id("textures/entity/bat/bat.png"), Material.OUTLINE_CULL, false, times(0), cameras(0, 180), TargetContract.OUTLINE);
        add(fixtures, "outline_no_cull", Phase.C, "Cow no-cull outline", ModelLayers.COW, id("textures/entity/cow/cow_temperate.png"), Material.OUTLINE_NO_CULL, false, times(0), cameras(0, 180), TargetContract.OUTLINE);
        add(fixtures, "water_mask", Phase.C, "Oak boat water patch", ModelLayers.BOAT_WATER_PATCH, null, Material.WATER_MASK, false, times(0), cameras(0), TargetContract.DEPTH_ONLY);
        return fixtures;
    }

    private static void add(Map<String, Fixture> fixtures, String canonical, Phase phase, String name,
                            ModelLayerLocation layer, Identifier texture, Material material, boolean sorted,
                            List<Float> times, List<Float> cameras, TargetContract targets) {
        add(fixtures, canonical, phase, name, layer, texture, List.of(), material, sorted, times, cameras, targets);
    }

    private static void add(Map<String, Fixture> fixtures, String canonical, Phase phase, String name,
                            ModelLayerLocation layer, Identifier texture, List<Identifier> auxiliary,
                            Material material, boolean sorted, List<Float> times, List<Float> cameras,
                            TargetContract targets) {
        int tolerance=switch(material){case BANNER_PATTERN->2;case EYES,GLINT,ENTITY_CUTOUT,ENTITY_CUTOUT_Z_OFFSET,ENTITY_CUTOUT_DISSOLVE,ENTITY_TRANSLUCENT,ENTITY_TRANSLUCENT_EMISSIVE,ARMOR_CUTOUT,ARMOR_DECAL,ARMOR_TRANSLUCENT->1;default->0;};
        Fixture previous = fixtures.put(canonical, new Fixture(canonical, phase, name, layer, texture,
                List.copyOf(auxiliary), material, sorted, List.copyOf(times), List.copyOf(cameras), targets,tolerance,
                sorted?1.0e-8f:0.0f));
        if (previous != null) throw new IllegalStateException("Duplicate differential fixture: " + canonical);
    }

    public static List<Fixture> fixtures() { return List.copyOf(FIXTURES.values()); }
    public static Fixture require(String canonical) {
        Fixture fixture = FIXTURES.get(canonical);
        if (fixture == null) throw new IllegalArgumentException("Unknown canonical pipeline: " + canonical);
        return fixture;
    }
    public static List<Fixture> phase(Phase phase) {
        return FIXTURES.values().stream().filter(fixture -> fixture.phase() == phase).toList();
    }
    public static boolean contains(String canonical) { return FIXTURES.containsKey(canonical); }

    private static Identifier id(String path) { return Identifier.withDefaultNamespace(path); }
    private static List<Float> times(float... values) { return boxed(values); }
    private static List<Float> cameras(float... values) { return boxed(values); }
    private static List<Float> boxed(float[] values) {
        java.util.ArrayList<Float> result = new java.util.ArrayList<>(values.length);
        for (float value : values) result.add(value);
        return List.copyOf(result);
    }
}
