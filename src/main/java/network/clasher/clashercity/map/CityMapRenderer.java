package network.clasher.clashercity.map;

import network.clasher.clashercity.model.Area;
import network.clasher.clashercity.model.Plot;
import network.clasher.clashercity.storage.CityDatabase;
import network.clasher.clashercity.util.FormatUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a 128x128 top-down schematic map of city plots, roads, and public areas onto a Minecraft MapView.
 */
public final class CityMapRenderer extends MapRenderer {
    private final CityDatabase db;
    private BufferedImage cached;
    private long lastBuild;

    public CityMapRenderer(CityDatabase db) {
        super(false);
        this.db = db;
    }

    public void invalidate() {
        cached = null;
        lastBuild = 0L;
    }

    @Override
    public void render(MapView view, MapCanvas canvas, Player player) {
        if (cached == null || System.currentTimeMillis() - lastBuild > 5_000L) {
            cached = buildMapImage();
            lastBuild = System.currentTimeMillis();
        }
        canvas.drawImage(0, 0, cached);
    }

    public ItemStack createMapItem(World cityWorld) {
        MapView view = Bukkit.createMap(cityWorld);
        for (MapRenderer renderer : new ArrayList<>(view.getRenderers())) {
            view.removeRenderer(renderer);
        }
        view.setScale(MapView.Scale.CLOSEST);
        view.setTrackingPosition(false);
        view.addRenderer(this);

        ItemStack map = new ItemStack(Material.FILLED_MAP);
        if (map.getItemMeta() instanceof MapMeta meta) {
            meta.setMapView(view);
            meta.displayName(FormatUtil.noItalic(FormatUtil.mm("<aqua><bold>ClasherCity Map</bold></aqua>")));
            meta.lore(List.of(
                    FormatUtil.noItalic(FormatUtil.mm("<green>Green:</green> <gray>Available plots</gray>")),
                    FormatUtil.noItalic(FormatUtil.mm("<red>Red:</red> <gray>Purchased plots</gray>")),
                    FormatUtil.noItalic(FormatUtil.mm("<gray>Grey: Roads</gray>")),
                    FormatUtil.noItalic(FormatUtil.mm("<blue>Blue:</blue> <gray>Public areas</gray>"))
            ));
            map.setItemMeta(meta);
        }
        return map;
    }

    private BufferedImage buildMapImage() {
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(31, 38, 45));
            g.fillRect(0, 0, 128, 128);

            List<Plot> plots = db.getPlots();
            List<Area> areas = db.getAreas();
            int minX = 0, minZ = 0, maxX = 100, maxZ = 100;
            boolean any = false;
            for (Plot p : plots) {
                if (!any) { minX = p.minX(); maxX = p.maxX(); minZ = p.minZ(); maxZ = p.maxZ(); any = true; }
                else { minX = Math.min(minX, p.minX()); maxX = Math.max(maxX, p.maxX()); minZ = Math.min(minZ, p.minZ()); maxZ = Math.max(maxZ, p.maxZ()); }
            }
            for (Area a : areas) {
                if (!any) { minX = a.minX(); maxX = a.maxX(); minZ = a.minZ(); maxZ = a.maxZ(); any = true; }
                else { minX = Math.min(minX, a.minX()); maxX = Math.max(maxX, a.maxX()); minZ = Math.min(minZ, a.minZ()); maxZ = Math.max(maxZ, a.maxZ()); }
            }
            int width = Math.max(1, maxX - minX + 1);
            int depth = Math.max(1, maxZ - minZ + 1);
            double scale = Math.min(116.0 / width, 104.0 / depth);

            g.setColor(new Color(230, 230, 230));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
            g.drawString("CLASHER CITY", 5, 9);

            for (Area a : areas) {
                int x = 6 + (int) Math.round((a.minX() - minX) * scale);
                int y = 15 + (int) Math.round((a.minZ() - minZ) * scale);
                int w = Math.max(2, (int) Math.ceil((a.maxX() - a.minX() + 1) * scale));
                int h = Math.max(2, (int) Math.ceil((a.maxZ() - a.minZ() + 1) * scale));
                g.setColor(a.type().equalsIgnoreCase("ROAD") ? new Color(115, 121, 128) : new Color(70, 125, 185));
                int cw = Math.max(0, Math.min(w, 122 - x));
                int ch = Math.max(0, Math.min(h, 119 - y));
                if (cw > 0 && ch > 0) g.fillRect(x, y, cw, ch);
            }

            for (Plot p : plots) {
                int x = 6 + (int) Math.round((p.minX() - minX) * scale);
                int y = 15 + (int) Math.round((p.minZ() - minZ) * scale);
                int w = Math.max(2, (int) Math.ceil((p.maxX() - p.minX() + 1) * scale));
                int h = Math.max(2, (int) Math.ceil((p.maxZ() - p.minZ() + 1) * scale));
                int cw = Math.max(0, Math.min(w, 122 - x));
                int ch = Math.max(0, Math.min(h, 119 - y));
                g.setColor(p.isOwned() ? new Color(185, 63, 63) : new Color(42, 170, 85));
                if (cw > 0 && ch > 0) {
                    g.fillRect(x, y, cw, ch);
                    g.setColor(new Color(20, 25, 28));
                    g.setStroke(new BasicStroke(1));
                    g.drawRect(x, y, cw, ch);
                }
                if (w > 10 && h > 7) {
                    g.setColor(Color.WHITE);
                    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 7));
                    g.drawString(p.id(), x + 1, y + 8);
                }
            }

            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 7));
            g.drawString("Green: free  Red: sold", 5, 126);
        } catch (Exception ex) {
            g.setColor(Color.RED);
            g.drawString("MAP DATA ERROR", 8, 64);
        } finally {
            g.dispose();
        }
        return image;
    }
}
