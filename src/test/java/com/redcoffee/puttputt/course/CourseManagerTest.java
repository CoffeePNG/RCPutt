package com.redcoffee.puttputt.course;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redcoffee.puttputt.util.Bounds;
import com.redcoffee.puttputt.util.Vec3;
import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A course that survives a save but not a reload is worse than one that never saved, so these tests
 * push a fully-populated course through disk and back.
 */
class CourseManagerTest {

    private static final Logger LOG = Logger.getLogger("CourseManagerTest");

    private static CourseManager manager(File dir) {
        return new CourseManager(dir, LOG);
    }

    @Test
    void everyFieldSurvivesARoundTrip(@TempDir File dir) throws IOException {
        CourseManager saving = manager(dir);
        Course course = saving.create("dunes", "world");
        Hole hole = course.holeOrCreate(1);
        hole.setPar(3);
        hole.setTee(new Vec3(10.5, 65.0, -20.5));
        hole.setCup(new Vec3(30.5, 65.0, -20.5));
        hole.setBounds(Bounds.of(0, 64, -25, 40, 70, -15));
        hole.addTeleport(12, 65, -20, new Vec3(25.5, 65.0, -20.5), true);
        hole.materialOverrides().put("GREEN_TERRACOTTA", "GREEN");
        saving.saveAll();

        CourseManager loading = manager(dir);
        loading.loadAll();

        Course back = loading.course("dunes").orElseThrow();
        assertEquals("world", back.world());
        Hole h = back.holes().get(0);
        assertEquals(3, h.par());
        assertEquals(10.5, h.tee().x());
        assertEquals(30.5, h.cup().x());
        assertNotNull(h.bounds());
        assertEquals(0, h.bounds().minX());
        assertEquals(70, h.bounds().maxY());
        assertEquals(1, h.teleportCount(), "teleport pads must survive a restart");
        assertEquals("GREEN", h.materialOverrides().get("GREEN_TERRACOTTA"));
    }

    /** The headline bug: a course built but never explicitly saved used to die with the server. */
    @Test
    void anUnsavedCourseIsWrittenByAFlush(@TempDir File dir) throws IOException {
        CourseManager building = manager(dir);
        Course course = building.create("bogey", "world");
        course.holeOrCreate(1).setTee(new Vec3(1.5, 65.0, 1.5));

        assertTrue(building.hasUnsavedChanges(), "a brand new course is not on disk yet");
        assertEquals(1, building.flushDirty(), "the flush must write it");
        assertFalse(building.hasUnsavedChanges());

        CourseManager restarted = manager(dir);
        restarted.loadAll();
        assertTrue(restarted.exists("bogey"), "the course survives a restart");
        assertEquals(1.5, restarted.course("bogey").orElseThrow().holes().get(0).tee().x());
    }

    /** A flush that rewrote every course every minute would churn disk for nothing. */
    @Test
    void aFlushSkipsCoursesNobodyTouched(@TempDir File dir) throws IOException {
        CourseManager building = manager(dir);
        building.create("quiet", "world").holeOrCreate(1).setPar(3);
        building.flushDirty();

        assertEquals(0, building.flushDirty(), "nothing changed, so nothing is written");

        building.course("quiet").orElseThrow().holeOrCreate(2).setPar(4);
        assertEquals(1, building.flushDirty(), "an edit makes it dirty again");
    }

    /**
     * A hole hands out its live override map, so an edit can happen without touching any setter.
     * The flush has to notice that too - which is why dirtiness is a content diff, not a flag.
     */
    @Test
    void anEditThroughTheLiveOverrideMapCountsAsDirty(@TempDir File dir) throws IOException {
        CourseManager building = manager(dir);
        Course course = building.create("sneaky", "world");
        Hole hole = course.holeOrCreate(1);
        building.flushDirty();
        assertFalse(building.hasUnsavedChanges());

        hole.materialOverrides().put("SAND", "SAND");

        assertTrue(building.hasUnsavedChanges(), "a mutation through the live map is still an edit");
        assertEquals(1, building.flushDirty());
    }

    /** A reload flushes first, so reloading must not be a way to lose the hole you just built. */
    @Test
    void editsSurviveALoadAllWhenFlushedFirst(@TempDir File dir) throws IOException {
        CourseManager manager = manager(dir);
        manager.create("links", "world").holeOrCreate(1).setPar(5);

        manager.flushDirty();
        manager.loadAll();

        assertEquals(5, manager.course("links").orElseThrow().holes().get(0).par());
    }

    /** A course id is matched case-insensitively, so its file must not be found only by luck. */
    @Test
    void aCourseCreatedWithMixedCaseReloads(@TempDir File dir) throws IOException {
        CourseManager saving = manager(dir);
        saving.create("Dunes", "world").holeOrCreate(1).setPar(4);
        saving.saveAll();

        CourseManager loading = manager(dir);
        loading.loadAll();

        assertTrue(loading.exists("dunes"), "lowercase lookup");
        assertTrue(loading.exists("Dunes"), "the id it was created with");
        assertEquals(4, loading.course("Dunes").orElseThrow().holes().get(0).par());
    }

    /** Holes must come back in playing order, not in whatever order the file happened to list them. */
    @Test
    void holesReloadInOrder(@TempDir File dir) throws IOException {
        CourseManager saving = manager(dir);
        Course course = saving.create("links", "world");
        for (int n : new int[]{3, 1, 2}) {
            course.holeOrCreate(n).setPar(n);
        }
        saving.saveAll();

        CourseManager loading = manager(dir);
        loading.loadAll();

        Course back = loading.course("links").orElseThrow();
        assertEquals(3, back.holes().size());
        assertEquals(1, back.holes().get(0).number());
        assertEquals(2, back.holes().get(1).number());
        assertEquals(3, back.holes().get(2).number());
    }
}
