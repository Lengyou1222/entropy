/*
 * File: scripts/GenIcon.java
 *
 * 应用图标生成器：黑底 + 居中"熵"字。输出自适应图标（adaptive icon）的
 * foreground / monochrome 分层素材，以及 Android 7.x 仍在读的 legacy 位图。
 *
 * 为什么用代码生成而不是手画：
 *  "熵"是汉字，矢量路径手写不出来；把字体轮廓烘成位图是唯一既能自动重跑、
 *  又不依赖外部设计稿的做法。换字形或改字号，改参数重跑一遍就行。
 *
 * 尺寸按自适应图标规范：图标画布 108dp，可安全显示区是正中间直径 66dp 的圆，
 *  四角会被启动器遮罩裁掉。正方形汉字要整个落进这个圆，边长上限 66/sqrt(2)≈46.7dp，
 *  所以这里取画布的 0.42 倍（约 45dp），圆形遮罩下不会被切笔。
 *
 * 用法（项目根目录）：
 *   javac -d "$TEMP/genicon" scripts/GenIcon.java
 *   java -cp "$TEMP/genicon" GenIcon app/src/main/res
 */
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class GenIcon {

    /** 自适应图标画布边长（dp） */
    private static final int ADAPTIVE_BASE = 108;
    /** legacy 启动器图标边长（dp） */
    private static final int LEGACY_BASE = 48;
    /** 超采样倍数：先按 4 倍尺寸画字再缩小，避免小尺寸下笔画发虚 */
    private static final int SS = 4;
    private static final char GLYPH = '熵';

    private static final String[][] DENSITIES = {
        {"mdpi", "1"}, {"hdpi", "1.5"}, {"xhdpi", "2"}, {"xxhdpi", "3"}, {"xxxhdpi", "4"},
    };

    public static void main(String[] args) throws Exception {
        File resDir = new File(args.length > 0 ? args[0] : "app/src/main/res");
        Font font = pickFont();
        System.out.println("glyph font: " + font.getFontName());

        for (String[] d : DENSITIES) {
            float mul = Float.parseFloat(d[1]);
            int adaptive = Math.round(ADAPTIVE_BASE * mul);
            int legacy = Math.round(LEGACY_BASE * mul);

            // adaptive 层的底色由 <background> 这一层给（纯黑），这里只出透明底白字
            write(new File(resDir, "drawable-" + d[0] + "/ic_launcher_foreground.png"),
                    glyphLayer(font, adaptive, 0.42f, Color.WHITE));
            write(new File(resDir, "drawable-" + d[0] + "/ic_launcher_monochrome.png"),
                    glyphLayer(font, adaptive, 0.42f, Color.WHITE));

            File mipmap = new File(resDir, "mipmap-" + d[0]);
            write(new File(mipmap, "ic_launcher.png"), legacyIcon(font, legacy, false));
            write(new File(mipmap, "ic_launcher_round.png"), legacyIcon(font, legacy, true));
        }
        System.out.println("icons written under " + resDir.getPath());
    }

    /**
     * 选一个真能画出"熵"的字体。
     * 顺序：黑体（笔画粗，小尺寸下最清楚）→ 雅黑 → 宋体 → Noto → 逻辑字体兜底。
     * canDisplay 这一步不能省：字体缺字时 Java2D 静默画成豆腐块，
     * 而豆腐块进了图标资源，要到装机看桌面才发现。
     */
    private static Font pickFont() throws Exception {
        String[] files = {"simhei.ttf", "msyh.ttc", "msyhbd.ttc", "simsun.ttc", "NotoSansSC-VF.ttf"};
        File fontDir = new File(windir() + "/Fonts");
        for (String name : files) {
            File f = new File(fontDir, name);
            if (!f.isFile()) continue;
            try {
                Font big = Font.createFont(Font.TRUETYPE_FONT, f).deriveFont(Font.PLAIN, 100f);
                if (big.canDisplay(GLYPH)) return big;
            } catch (Exception ignored) {
                // 集合字体（.ttc）在个别 JDK 上取不到第一面，跳过
            }
        }
        Font fallback = new Font("SansSerif", Font.PLAIN, 100);
        if (!fallback.canDisplay(GLYPH)) {
            throw new IllegalStateException("找不到能显示 \"" + GLYPH + "\" 的字体");
        }
        return fallback;
    }

    private static String windir() {
        String w = System.getenv("WINDIR");
        return w == null || w.isEmpty() ? "C:/Windows" : w;
    }

    /** 透明底 + 居中白字：adaptive icon 的 foreground / monochrome 层 */
    private static BufferedImage glyphLayer(Font font, int size, float fraction, Color color) {
        BufferedImage hi = new BufferedImage(size * SS, size * SS, BufferedImage.TYPE_INT_ARGB);
        drawGlyph(hi, font, fraction, color);
        return downscale(hi, size);
    }

    /**
     * legacy 图标：Android 7 的启动器不裁角，黑底要自己画满。
     * 字给到 0.60 倍 —— 没有 66dp 安全区的约束，就该把画面用满。
     */
    private static BufferedImage legacyIcon(Font font, int size, boolean round) {
        BufferedImage hi = new BufferedImage(size * SS, size * SS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = hi.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        int w = hi.getWidth();
        if (round) {
            g.fillOval(0, 0, w, w);
        } else {
            g.fillRoundRect(0, 0, w, w, Math.round(w * 0.18f), Math.round(w * 0.18f));
        }
        g.dispose();
        drawGlyph(hi, font, 0.60f, Color.WHITE);
        return downscale(hi, size);
    }

    /**
     * 画一个光学居中的字。
     * 居中取 glyphVector 的实际墨水包围盒，不拿 fontMetrics 的行高算：
     * 汉字 descent 很大，按基线居中会整体偏上，肉眼看到的就是"没对齐"。
     */
    private static void drawGlyph(BufferedImage img, Font base, float fraction, Color color) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(color);

        int size = img.getWidth();
        Font f = base.deriveFont(Font.PLAIN, size * fraction);
        String text = String.valueOf(GLYPH);
        Rectangle2D ink = f.createGlyphVector(g.getFontRenderContext(), text).getVisualBounds();
        float x = (float) ((size - ink.getWidth()) / 2.0 - ink.getX());
        float y = (float) ((size - ink.getHeight()) / 2.0 - ink.getY());
        g.setFont(f);
        g.drawString(text, x, y);
        g.dispose();
    }

    private static BufferedImage downscale(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setComposite(AlphaComposite.Src);
        g.drawImage(src, AffineTransform.getScaleInstance(
                size / (double) src.getWidth(), size / (double) src.getHeight()), null);
        g.dispose();
        return out;
    }

    private static void write(File file, BufferedImage img) throws Exception {
        file.getParentFile().mkdirs();
        ImageIO.write(img, "png", file);
        System.out.println("  " + file.getPath() + "  " + img.getWidth() + "x" + img.getHeight());
    }
}
