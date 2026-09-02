package com.redcoffee.puttputt.course;

import com.redcoffee.puttputt.util.Bounds;
import com.redcoffee.puttputt.util.Vec3;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Loads and saves courses as one YAML file per course under {@code courses/}. Geometry is
 * config-shaped and git-diffable; the in-world builder writes through this class so a course can
 * still be hand-patched in a pinch.
 */
public final class CourseManager {

    private final File directory;
    private final Logger logger;
    private final Map<String, Course> courses = new ConcurrentHashMap<>();
    /**
     * The YAML last written (or read) for each course, so a flush can skip courses nobody touched.
     *
     * <p>Comparing the serialised form rather than tracking a dirty flag is deliberate: a hole hands
     * out its live material-override map, so an edit can happen without passing through any setter
     * a flag could hang off. Rendering the course and diffing the text cannot miss one.
     */
    private final Map<String, String> lastWritten = new ConcurrentHashMap<>();

    public CourseManager(File directory, Logger logger) {
        this.directory = directory;
        this.logger = logger;
    }

    public void loadAll() {
        courses.clear();
        lastWritten.clear();
        if (!directory.exists() && !directory.mkdirs()) {
            logger.warning("Could not create course directory " + directory);
            return;
        }
        File[] files = directory.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            try {
                Course course = read(file);
                courses.put(key(course.id()), course);
                // Seed the baseline so a course nobody has edited is not rewritten on first flush.
                lastWritten.put(key(course.id()), serialize(course));
            } catch (RuntimeException ex) {
                // One malformed course must not take the whole plugin down with it.
                logger.log(Level.WARNING, "Skipping unreadable course file " + file.getName(), ex);
            }
        }
        logger.info("Loaded " + courses.size() + " course(s).");
    }

    public Optional<Course> course(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(courses.get(key(id)));
    }

    public Collection<Course> courses() {
        return List.copyOf(courses.values());
    }

    public List<String> courseIds() {
        return courses.values().stream().map(Course::id).sorted().toList();
    }

    public boolean exists(String id) {
        return courses.containsKey(key(id));
    }

    public Course create(String id, String world) {
        Course course = new Course(id, id, world);
        courses.put(key(id), course);
        return course;
    }

    public boolean delete(String id) {
        lastWritten.remove(key(id));
        Course removed = courses.remove(key(id));
        if (removed == null) {
            return false;
        }
        File file = fileFor(removed.id());
        return !file.exists() || file.delete();
    }

    public void saveAll() throws IOException {
        for (Course course : courses.values()) {
            save(course);
        }
    }

    /**
     * Writes only the courses whose content has actually changed.
     *
     * <p>Called on a timer and at shutdown, because a course that lives in memory until someone
     * remembers to type {@code save} is a course that a restart eats.
     *
     * @return how many files were written
     */
    public int flushDirty() throws IOException {
        int written = 0;
        for (Course course : courses.values()) {
            if (!serialize(course).equals(lastWritten.get(key(course.id())))) {
                save(course);
                written++;
            }
        }
        return written;
    }

    /** True when some course has edits that are not on disk yet. */
    public boolean hasUnsavedChanges() {
        for (Course course : courses.values()) {
            if (!serialize(course).equals(lastWritten.get(key(course.id())))) {
                return true;
            }
        }
        return false;
    }

    public void save(Course course) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create course directory " + directory);
        }
        String rendered = serialize(course);
        java.nio.file.Files.writeString(fileFor(course.id()).toPath(), rendered);
        lastWritten.put(key(course.id()), rendered);
    }

    /** The course as it would appear on disk. */
    private String serialize(Course course) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("id", course.id());
        yaml.set("display", course.displayName());
        yaml.set("world", course.world());

        List<Map<String, Object>> holes = new ArrayList<>();
        for (Hole hole : course.holes()) {
            Map<String, Object> node = new java.util.LinkedHashMap<>();
            node.put("number", hole.number());
            node.put("par", hole.par());
            if (hole.tee() != null) {
                node.put("tee", vecToMap(hole.tee()));
            }
            if (hole.cup() != null) {
                node.put("cup", vecToMap(hole.cup()));
            }
            if (hole.bounds() != null) {
                Bounds b = hole.bounds();
                node.put("bounds", Map.of(
                        "min", List.of(b.minX(), b.minY(), b.minZ()),
                        "max", List.of(b.maxX(), b.maxY(), b.maxZ())));
            }
            if (hole.teleportCount() > 0) {
                List<Map<String, Object>> pads = new ArrayList<>();
                for (Map.Entry<int[], com.redcoffee.puttputt.game.Teleport> entry : hole.teleportEntries()) {
                    int[] cell = entry.getKey();
                    Vec3 to = entry.getValue().destination();
                    pads.add(new java.util.LinkedHashMap<>(Map.of(
                            "from", List.of(cell[0], cell[1], cell[2]),
                            "to", Map.of("x", to.x(), "y", to.y(), "z", to.z()),
                            "keep_velocity", entry.getValue().keepVelocity())));
                }
                node.put("teleports", pads);
            }
            if (!hole.materialOverrides().isEmpty()) {
                node.put("surface_overrides", Map.of("material_map", Map.copyOf(hole.materialOverrides())));
            }
            holes.add(node);
        }
        yaml.set("holes", holes);
        return yaml.saveToString();
    }

    private Course read(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String id = yaml.getString("id", stripExtension(file.getName()));
        Course course = new Course(id, yaml.getString("display", id), yaml.getString("world"));

        for (Map<?, ?> raw : yaml.getMapList("holes")) {
            Object numberValue = raw.get("number");
            if (!(numberValue instanceof Number number)) {
                logger.warning("Course " + id + " has a hole with no number; skipping it.");
                continue;
            }
            Hole hole = course.holeOrCreate(number.intValue());
            if (raw.get("par") instanceof Number par) {
                hole.setPar(par.intValue());
            }
            hole.setTee(mapToVec(raw.get("tee")));
            hole.setCup(mapToVec(raw.get("cup")));
            hole.setBounds(readBounds(raw.get("bounds")));
            readOverrides(raw.get("surface_overrides"), hole);
            readTeleports(raw.get("teleports"), hole, id);
        }
        return course;
    }

    private void readTeleports(Object node, Hole hole, String courseId) {
        if (!(node instanceof List<?> list)) {
            return;
        }
        for (Object element : list) {
            Map<?, ?> pad = asMap(element);
            if (pad == null) {
                continue;
            }
            int[] from = readTriple(pad.get("from"));
            Vec3 to = mapToVec(pad.get("to"));
            if (from == null || to == null) {
                logger.warning("Course " + courseId + " hole " + hole.number()
                        + " has a teleport with no valid from/to; skipping it.");
                continue;
            }
            boolean keepVelocity = !(pad.get("keep_velocity") instanceof Boolean keep) || keep;
            hole.addTeleport(from[0], from[1], from[2], to, keepVelocity);
        }
    }

    private static void readOverrides(Object node, Hole hole) {
        Map<?, ?> overrides = asMap(node);
        if (overrides == null) {
            return;
        }
        Map<?, ?> materialMap = asMap(overrides.get("material_map"));
        if (materialMap == null) {
            return;
        }
        materialMap.forEach((material, surface) -> {
            if (material != null && surface != null) {
                hole.materialOverrides().put(
                        material.toString().trim().toUpperCase(Locale.ROOT),
                        surface.toString().trim().toUpperCase(Locale.ROOT));
            }
        });
    }

    private static Bounds readBounds(Object node) {
        Map<?, ?> map = asMap(node);
        if (map == null) {
            return null;
        }
        int[] min = readTriple(map.get("min"));
        int[] max = readTriple(map.get("max"));
        if (min == null || max == null) {
            return null;
        }
        return Bounds.of(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    private static int[] readTriple(Object node) {
        if (!(node instanceof List<?> list) || list.size() < 3) {
            return null;
        }
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            if (!(list.get(i) instanceof Number n)) {
                return null;
            }
            out[i] = n.intValue();
        }
        return out;
    }

    private static Map<String, Object> vecToMap(Vec3 vec) {
        return Map.of("x", vec.x(), "y", vec.y(), "z", vec.z());
    }

    private static Vec3 mapToVec(Object node) {
        Map<?, ?> map = asMap(node);
        if (map == null) {
            return null;
        }
        Object x = map.get("x");
        Object y = map.get("y");
        Object z = map.get("z");
        if (x instanceof Number nx && y instanceof Number ny && z instanceof Number nz) {
            return new Vec3(nx.doubleValue(), ny.doubleValue(), nz.doubleValue());
        }
        return null;
    }

    /** Handles both raw maps and Bukkit's {@link ConfigurationSection} wrappers. */
    private static Map<?, ?> asMap(Object node) {
        if (node instanceof Map<?, ?> map) {
            return map;
        }
        if (node instanceof ConfigurationSection section) {
            return section.getValues(false);
        }
        return null;
    }

    private File fileFor(String id) {
        return new File(directory, id + ".yml");
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static String key(String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
