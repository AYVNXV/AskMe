package com.ayyan.askme.gui;

import com.ayyan.askme.AskMe;
import com.ayyan.askme.config.AskMeConfig;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ImageWidget;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Full-screen background for the Ask Me settings screens.
 *
 * <p>Default: the picture bundled in the mod (assets/ask-me/textures/gui/background.jpg).
 * Custom: the player's own picture in config/askme-bg.png. A picture can be set by dropping an image file on the
 * settings screen, or with /askme background &lt;path&gt;.</p>
 *
 * <p>The picture is scaled in Java (cover mode, no stretching) to exactly the size of the screen in GUI units
 * and darkened by the "dim" value, so buttons stay readable. The result is cached until the screen size, the
 * dim value or the picture changes.</p>
 */
public final class BackgroundHelper {

    /** The bundled picture is background.jpg; png is kept as a fallback. */
    private static final String[] DEFAULT_RESOURCES = {
            "/assets/ask-me/textures/gui/background.jpg",
            "/assets/ask-me/textures/gui/background.png"
    };
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(AskMe.MOD_ID, "settings_background");
    private static final int MAX_SIZE = 4096;

    private static String lastKey = "";
    private static boolean textureRegistered;
    private static BufferedImage sourceCache;
    private static String sourceKey = "";

    private BackgroundHelper() {
    }

    public static Path customFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("askme-bg.png");
    }

    public static boolean hasCustomFile() {
        return Files.isRegularFile(customFile());
    }

    /** Widgets to add FIRST in init() of a settings screen. Empty list if anything fails (no background then). */
    public static List<AbstractWidget> create(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAX_SIZE || height > MAX_SIZE) {
            return List.of();
        }
        try {
            ensureTexture(width, height);
            return List.of(ImageWidget.texture(width, height, TEXTURE, width, height));
        } catch (Throwable problem) {
            AskMe.LOGGER.warn("[Ask Me] Could not build the settings background: {}", problem.toString());
            return List.of();
        }
    }

    /** Forget the cached picture (after the custom file or a setting changed). */
    public static void invalidate() {
        lastKey = "";
        sourceKey = "";
        sourceCache = null;
    }

    /**
     * Uses the given image file as the custom background. Any format Java can read works (png, jpg, gif, bmp).
     * Throws IOException with a short message if the file cannot be used.
     */
    public static void setCustomFrom(Path file) throws IOException {
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) {
            throw new IOException("This image format is not supported. Use png or jpg.");
        }
        if (image.getWidth() > 8192 || image.getHeight() > 8192) {
            throw new IOException("The image is too big (max 8192 x 8192).");
        }
        Files.createDirectories(customFile().getParent());
        ImageIO.write(image, "png", customFile().toFile());
        AskMeConfig config = AskMeConfig.get();
        config.useCustomBackground = true;
        config.save();
        invalidate();
    }

    public static void useDefault() {
        AskMeConfig config = AskMeConfig.get();
        config.useCustomBackground = false;
        config.save();
        invalidate();
    }

    // ------------------------------------------------------------------ internals

    private static void ensureTexture(int width, int height) throws Exception {
        AskMeConfig config = AskMeConfig.get();
        boolean custom = config.useCustomBackground && hasCustomFile();
        long stamp = custom ? Files.getLastModifiedTime(customFile()).toMillis() : 0L;
        String key = width + "x" + height + "|" + config.backgroundDim + "|" + custom + "|" + stamp;
        if (key.equals(lastKey) && textureRegistered) {
            return;
        }

        BufferedImage source = loadSource(custom, stamp);
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            double scale = Math.max(width / (double) source.getWidth(), height / (double) source.getHeight());
            int drawW = (int) Math.ceil(source.getWidth() * scale);
            int drawH = (int) Math.ceil(source.getHeight() * scale);
            g.drawImage(source, (width - drawW) / 2, (height - drawH) / 2, drawW, drawH, null);
            int alpha = Math.max(0, Math.min(90, config.backgroundDim)) * 255 / 100;
            if (alpha > 0) {
                g.setColor(new Color(0, 0, 0, alpha));
                g.fillRect(0, 0, width, height);
            }
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(target, "png", bytes);
        NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes.toByteArray()));

        TextureManager manager = Minecraft.getInstance().getTextureManager();
        if (textureRegistered) {
            try {
                manager.release(TEXTURE);
            } catch (Throwable ignored) {
                // old texture stays until replaced
            }
        }
        manager.register(TEXTURE, createTexture(image));
        textureRegistered = true;
        lastKey = key;
    }

    private static BufferedImage loadSource(boolean custom, long stamp) throws IOException {
        String key = custom ? "custom|" + stamp : "default";
        if (sourceCache != null && key.equals(sourceKey)) {
            return sourceCache;
        }
        BufferedImage image = null;
        if (custom) {
            image = ImageIO.read(customFile().toFile());
        }
        if (image == null) {
            for (String resource : DEFAULT_RESOURCES) {
                try (InputStream in = BackgroundHelper.class.getResourceAsStream(resource)) {
                    if (in != null) {
                        image = ImageIO.read(in);
                        if (image != null) {
                            break;
                        }
                    }
                }
            }
            if (image == null) {
                throw new IOException("Default background is missing in the mod file.");
            }
            key = "default";
        }
        sourceCache = image;
        sourceKey = key;
        return image;
    }

    /** DynamicTexture has different constructors in different game versions, so it is found by reflection. */
    private static AbstractTexture createTexture(NativeImage image) throws ReflectiveOperationException {
        for (Constructor<?> constructor : DynamicTexture.class.getConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length == 2 && types[0] == Supplier.class && types[1] == NativeImage.class) {
                Supplier<String> label = () -> "askme_settings_background";
                return (AbstractTexture) constructor.newInstance(label, image);
            }
            if (types.length == 1 && types[0] == NativeImage.class) {
                return (AbstractTexture) constructor.newInstance(image);
            }
        }
        throw new NoSuchMethodException("No usable DynamicTexture constructor found");
    }
}
