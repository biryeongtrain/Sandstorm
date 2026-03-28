package de.tomalbrc.sandstorm.util;

import de.tomalbrc.sandstorm.Sandstorm;
import de.tomalbrc.sandstorm.component.ParticleComponents;
import de.tomalbrc.sandstorm.component.particle.ParticleAppearanceBillboard;
import de.tomalbrc.sandstorm.io.ParticleEffectFile;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import gg.moonflower.molangcompiler.api.MolangEnvironment;
import gg.moonflower.molangcompiler.api.MolangRuntime;
import gg.moonflower.molangcompiler.api.exception.MolangRuntimeException;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.resources.Identifier;
import org.joml.Vector2i;
import org.joml.Vector4i;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

public class ParticleModels {
    private static final Identifier PARTICLE_ATLAS = Identifier.fromNamespaceAndPath("minecraft", "gui");
    private static final String ATLAS_PATH = "assets/minecraft/atlases/gui.json";
    private static final byte[] ATLAS_DATA = """
            {
              "sources": [
                {
                  "type": "minecraft:directory",
                  "prefix": "",
                  "source": "gui/sprites"
                },
                {
                  "type": "minecraft:directory",
                  "prefix": "mob_effect/",
                  "source": "mob_effect"
                },
                {
                  "type": "minecraft:directory",
                  "prefix": "sandstorm/generated/",
                  "source": "gui/sandstorm/generated"
                }
              ]
            }
            """.getBytes(StandardCharsets.UTF_8);

    private static final Map<ParticleEffectFile, Int2ObjectArrayMap<SpriteFrame>> FRAME_DATA = new Object2ObjectOpenHashMap<>();
    private static final Map<String, byte[]> RESOURCE_DATA = new Object2ObjectOpenHashMap<>();

    public static Identifier atlasId() {
        return PARTICLE_ATLAS;
    }

    public static SpriteFrame frameData(ParticleEffectFile effectFile, int flipbookRnd, float normalizedLifetime, MolangEnvironment environment) throws MolangRuntimeException {
        var billboard = effectFile.effect.components.get(ParticleComponents.PARTICLE_APPEARANCE_BILLBOARD);
        var map = FRAME_DATA.get(effectFile);
        if (billboard != null && billboard.uv != null && billboard.uv.flipbook != null && billboard.uv.flipbook.stretch_to_lifetime) {
            var max = environment.resolve(billboard.uv.flipbook.max_frame);

            int rndIndex = flipbookRnd * 1000;
            boolean containsRndIndex = false;
            for (Int2ObjectMap.Entry<SpriteFrame> entry : map.int2ObjectEntrySet()) {
                if (entry.getIntKey() == rndIndex) {
                    containsRndIndex = true;
                    break;
                }
            }

            return map.get((int) ((containsRndIndex ? rndIndex : 0) + Math.min(normalizedLifetime, 1.0f) * (max - 1)));
        }

        int idx = map.size() <= 1 ? 0 : (int) (Math.random() * map.size());
        return map.get(map.keySet().toIntArray()[idx]);
    }

    public static SpriteFrame frameData(ParticleEffectFile effectFile, int flipbookRnd, int index) {
        var list = FRAME_DATA.get(effectFile);
        int rndIndex = flipbookRnd * 1000;
        boolean containsRndIndex = false;
        for (Int2ObjectMap.Entry<SpriteFrame> entry : list.int2ObjectEntrySet()) {
            if (entry.getIntKey() == rndIndex) {
                containsRndIndex = true;
                break;
            }
        }
        return list.get((containsRndIndex ? rndIndex : 0) + index);
    }

    public static void addToResourcePack(ResourcePackBuilder builder) {
        RESOURCE_DATA.put(ATLAS_PATH, ATLAS_DATA);
        for (Map.Entry<String, byte[]> entry : RESOURCE_DATA.entrySet()) {
            builder.addData(entry.getKey(), entry.getValue());
        }
    }

    public static void addFrom(ParticleEffectFile effectFile, InputStream imageStream) throws IOException, MolangRuntimeException {
        var billboard = effectFile.effect.components.get(ParticleComponents.PARTICLE_APPEARANCE_BILLBOARD);

        BufferedImage image = ImageIO.read(imageStream);
        handleUV(effectFile, image, billboard);
        handleLifetimeFlipbook(effectFile, image, billboard);
    }

    private static void handleUV(ParticleEffectFile effectFile, BufferedImage image, ParticleAppearanceBillboard billboard) throws IOException, MolangRuntimeException {
        Int2ObjectArrayMap<SpriteFrame> map = new Int2ObjectArrayMap<>();
        if (billboard != null && billboard.uv != null) {
            ObjectOpenHashSet<Vector4i> vecs = new ObjectOpenHashSet<>();
            for (int i = 0; i < 10; i++) {
                var builder = MolangRuntime.runtime();
                builder.setVariable("particle_random_1", (float) Math.random());
                builder.setVariable("particle_random_2", (float) Math.random());
                builder.setVariable("particle_random_3", (float) Math.random());
                builder.setVariable("particle_random_4", (float) Math.random());
                var runtime = builder.create();

                if (billboard.uv.uv != null) {
                    boolean texel = billboard.uv.textureWidth != 1 && billboard.uv.textureHeight != 1;
                    float xs = texel ? 1.f : billboard.uv.textureWidth;
                    float ys = texel ? 1.f : billboard.uv.textureHeight;

                    var x = (int) (runtime.resolve(billboard.uv.uv[0]) * xs);
                    var y = (int) (runtime.resolve(billboard.uv.uv[1]) * ys);
                    var w = (int) (runtime.resolve(billboard.uv.uvSize[0]) * xs);
                    var h = (int) (runtime.resolve(billboard.uv.uvSize[1]) * ys);
                    Vector4i n = new Vector4i(x,y,w,h);
                    if (vecs.contains(n))
                        continue;

                    vecs.add(n);

                    BufferedImage newImage = image.getSubimage(x, y, w, h);
                    map.put(map.size(), createFrame(newImage));
                }
            }
            FRAME_DATA.put(effectFile, map);
        }
        else {
            map.put(map.size(), createFrame(image));
            FRAME_DATA.put(effectFile, map);
        }
    }

    private static void handleLifetimeFlipbook(ParticleEffectFile effectFile, BufferedImage image, ParticleAppearanceBillboard billboard) throws IOException, MolangRuntimeException {
        if (billboard != null && billboard.uv != null && billboard.uv.flipbook != null) {
            boolean normalized = billboard.uv.textureWidth == 1 || billboard.uv.textureHeight == 1;
            float xs = !normalized ? 1.f : (float) image.getWidth() / billboard.uv.textureWidth;
            float ys = !normalized ? 1.f : (float) image.getHeight() / billboard.uv.textureHeight;

            ParticleAppearanceBillboard.Flipbook flipbook = billboard.uv.flipbook;
            int maxFrame = (int) flipbook.max_frame.getConstant();

            Int2ObjectArrayMap<SpriteFrame> map = new Int2ObjectArrayMap<>();
            ObjectOpenHashSet<Vector2i> vecs = new ObjectOpenHashSet<>();
            int realI = 0;
            for (int randomIterator = 0; randomIterator < 10; randomIterator++) {
                var builder = MolangRuntime.runtime();
                builder.setVariable("particle_random_1", (float) Math.random());
                builder.setVariable("particle_random_2", (float) Math.random());
                builder.setVariable("particle_random_3", (float) Math.random());
                builder.setVariable("particle_random_4", (float) Math.random());
                var runtime = builder.create();

                int a = (int)runtime.resolve(flipbook.base_UV[0]);
                int b = (int)runtime.resolve(flipbook.base_UV[1]);
                Vector2i n = new Vector2i(a,b);
                if (vecs.contains(n))
                    continue;

                vecs.add(n);

                for (int currentFrame = 0; currentFrame < maxFrame; currentFrame++) {
                    var sub = image.getSubimage(
                            (int) ((a + currentFrame*flipbook.step_UV[0]) * xs),
                            (int) ((b + currentFrame*flipbook.step_UV[1]) * ys),
                            (int) ((flipbook.size_UV[0]) * xs),
                            (int) ((flipbook.size_UV[1]) * ys)
                    );

                    map.put(realI * 1000 + currentFrame, createFrame(sub));
                } // fori

                realI++;
            }


            FRAME_DATA.put(effectFile, map);
        }
    }

    private static SpriteFrame createFrame(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("Could not encode particle frame as PNG");
        }

        UUID id = UUID.randomUUID();
        String texturePath = "assets/sandstorm/textures/gui/sandstorm/generated/" + id + ".png";
        RESOURCE_DATA.put(texturePath, out.toByteArray());
        return new SpriteFrame(Identifier.fromNamespaceAndPath(Sandstorm.MOD_ID, "sandstorm/generated/" + id));
    }

    public record SpriteFrame(Identifier sprite) {}
}
