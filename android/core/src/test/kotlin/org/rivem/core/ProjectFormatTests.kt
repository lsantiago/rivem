package org.rivem.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rivem.core.geometry.Matrix3
import org.rivem.core.project.Bathymetry
import org.rivem.core.project.CompatibilityIssue
import org.rivem.core.project.CrossSections
import org.rivem.core.project.Footage
import org.rivem.core.project.JSON
import org.rivem.core.project.JSONValue
import org.rivem.core.project.PIVResults
import org.rivem.core.project.ProjectError
import org.rivem.core.project.ProjectStage
import org.rivem.core.project.RiverProject
import org.rivem.core.project.RiverWorkspace
import org.rivem.core.project.UnitSystem
import org.rivem.core.project.VideoInfo
import org.rivem.core.project.VideoRange
import java.io.File
import java.util.GregorianCalendar
import java.util.TimeZone

/** Reading and writing the RIVeR desktop 3.8.3 project format (docs/desktop-format.md). Swift `ProjectFormatTests`. */
class ProjectFormatTests {
    private val fixture = "desktop-projects/oblique-pisco"

    @Test
    fun opensDesktopProjectWithWindowsPaths() {
        val project = RiverProject.open(TestData.projectCopy(fixture))
        val s = project.settings
        assertEquals(Footage.oblique, s.footage)
        assertEquals(UnitSystem.si, s.unitSystem)
        assertEquals(30.0, s.video?.fps)
        assertEquals(1920, s.video?.width)
        assertEquals(VideoRange(start = 0, end = 600, step = 2, factor = 1.0), s.videoRange)
        assertEquals(2.0 / 30.0, s.frameInterval!!, 1e-12)
        assertEquals(ProjectStage.crossSections, s.stage)
        assertEquals("Río Pisco", s.riverName)
        assertEquals(4, s.controlPoints?.pixels?.size)
        assertEquals(22.154, s.controlPoints?.distances?.first())
        assertEquals(emptyList<CompatibilityIssue>(), project.compatibilityIssues())
    }

    @Test
    fun crossSectionsKeepOrderNamesAndNaN() {
        val project = RiverProject.open(TestData.projectCopy(fixture))
        val xs = project.readCrossSections()!!
        assertEquals(listOf("CS_default_1", "Sección aguas abajo"), xs.sections.map { it.name })
        assertNotNull(xs.summary)
        val first = xs.sections[0]
        assertEquals(15, first.numStations)
        assertEquals(JSONValue.int(15), first.json["num_stations"])
        assertEquals(0.85, first.alpha)
        assertEquals(emptyList<String>(), first.missingKeys)
        assertTrue(first.json["depth"]!!.doubleArray!![2].isNaN())
        // Both the absolute Windows path and the bare file name resolve to the CSV inside the project.
        assertNotNull(project.bathymetryURL(first))
        assertNotNull(project.bathymetryURL(xs.sections[1]))
    }

    /**
     * Golden check with RIVeR's own `pisco` example: the stored matrix sends the section's pixel end points to the
     * stored real-world coordinates (rounded to cm in the example).
     */
    @Test
    fun desktopMatrixMapsSectionPixelsToWorld() {
        val project = RiverProject.open(TestData.projectCopy(fixture))
        val m = project.readMatrix()!!
        val section = project.readCrossSections()!!.sections.first()
        for ((px, w) in listOf(section.pixelLeft to section.worldLeft, section.pixelRight to section.worldRight)) {
            val p = m.apply(px!!)
            val expected = w!!
            assertEquals(expected.x, p.x, 0.005)
            assertEquals(expected.y, p.y, 0.005)
        }
        assertEquals(section.realWorldLength!!, section.worldLeft!!.distanceTo(section.worldRight!!), 1e-9)
    }

    @Test
    fun roundTripKeepsUnknownKeysAndValues() {
        val project = RiverProject.open(TestData.projectCopy(fixture))
        val original = project.settings.copy()
        val sections = project.readCrossSections()!!
        project.save()
        project.writeCrossSections(sections)

        val reopened = RiverProject.open(project.folder)
        assertEquals(original.json["future_desktop_key"], reopened.settings.json["future_desktop_key"])
        assertEquals(original.json["processing"], reopened.settings.json["processing"])
        assertEquals("Río Pisco", reopened.settings.riverName)
        assertEquals(RiverProject.xsectionsFile, reopened.settings.xsectionsPath)
        assertEquals(sections, reopened.readCrossSections())
        assertEquals(emptyList<CompatibilityIssue>(), reopened.compatibilityIssues())
        // Files are pure ASCII, so desktop reads them identically as UTF-8 or Latin-1.
        for (name in listOf(RiverProject.settingsFile, RiverProject.xsectionsFile)) {
            val bytes = File(project.folder, name).readBytes()
            assertTrue(name, bytes.all { it >= 0 }) // signed bytes: < 0x80
        }
    }

    @Test
    fun newMatrixInvalidatesSectionsLikeDesktop() {
        val project = RiverProject.open(TestData.projectCopy(fixture))
        val m = Matrix3.fromRows(listOf(listOf(0.01, 0.0, 1.0), listOf(0.0, -0.01, 2.0), listOf(0.0, 0.0, 1.0)))
        project.setMatrix(m)
        assertEquals(m, project.readMatrix())
        assertNull(project.settings.xsectionsPath)
        assertFalse(File(project.folder, RiverProject.xsectionsFile).exists())
        val reopened = RiverProject.open(project.folder)
        assertEquals(RiverProject.matrixFile, reopened.settings.transformationMatrixPath)
        assertEquals(
            project.settings.json["transformation"]?.objectValue?.get("roi"),
            reopened.settings.json["transformation"]?.objectValue?.get("roi"),
        )
    }

    @Test
    fun createProjectLikeDesktop() {
        val root = TestData.tempDirectory()
        val video = VideoInfo(filepath = "rio.mov", name = "rio.mov", duration = 12.5, fps = 29.97, width = 3840, height = 2160)
        val utc = TimeZone.getTimeZone("UTC")
        val cal = GregorianCalendar(utc)
        cal.clear()
        cal.set(2026, 10, 17, 9, 5) // month 10 = November
        val whenDate = cal.time

        val p = RiverProject.create(root, name = "rio", footage = Footage.uav, video = video, date = whenDate, timeZone = utc)
        assertEquals("20261117T0905", p.folder.name)
        assertEquals("rio", p.folder.parentFile.name)
        val reopened = RiverProject.open(p.folder)
        assertEquals("20261117T0905", reopened.settings.creationDate)
        assertEquals(JSONValue.int(30), reopened.settings.video?.json?.get("fps")) // desktop rounds fps
        assertEquals(JSONValue.double(0.03), reopened.settings.video?.json?.get("time_between_frames"))
        assertEquals(ProjectStage.created, reopened.settings.stage)
        assertEquals(emptyList<CompatibilityIssue>(), reopened.compatibilityIssues())

        // Same minute again: a new folder, never an overwrite.
        val second = RiverProject.create(root, name = "rio", footage = Footage.uav, video = video, date = whenDate, timeZone = utc)
        assertEquals("20261117T0905-2", second.folder.name)
        assertEquals(2, RiverWorkspace.projectFolders(root).size)
    }

    @Test
    fun frameNamingAndListing() {
        assertEquals("0000000088.jpg", RiverProject.frameFileName(88))
        assertEquals(listOf(2, 4, 6, 8), VideoRange(start = 1, end = 10, step = 2).frameIndices)
        assertEquals(listOf(0, 3, 6), VideoRange(start = 0, end = 7, step = 3).frameIndices)

        val project = RiverProject.open(TestData.projectCopy(fixture))
        val frames = File(project.folder, "frames")
        for (name in listOf("0000000004.jpg", "0000000000.jpg", "background.jpg", "0000000002.jpg", "notes.txt")) {
            File(frames, name).writeBytes(byteArrayOf(0xFF.toByte()))
        }
        assertEquals(listOf("0000000000.jpg", "0000000002.jpg", "0000000004.jpg"), project.frameFiles.map { it.name })
    }

    @Test
    fun missingPiecesAreReported() {
        val folder = TestData.projectCopy(fixture)
        File(folder, "frames").deleteRecursively()
        File(folder, "xsections.json").delete()
        val issues = RiverProject.open(folder).compatibilityIssues().map { it.message }
        assertTrue("$issues", issues.contains("falta la carpeta frames/"))
        assertTrue("$issues", issues.any { it.contains("xsections.json") })
        val error = assertThrows(ProjectError::class.java) { RiverProject.open(folder.parentFile) }
        assertEquals(ProjectError.NoSettingsFile(), error)
    }

    @Test
    fun basenameAcceptsBothSeparators() {
        assertEquals("xsections.json", RiverProject.basename("""C:\Users\x\RIVeR\p\xsections.json"""))
        assertEquals("pisco_section.csv", RiverProject.basename("/Users/x/p/pisco_section.csv"))
        assertEquals("pisco_section.csv", RiverProject.basename("pisco_section.csv"))
    }

    @Test
    fun safeSectionNames() {
        assertEquals("Seccion_1", CrossSections.safeName("Sección 1"))
        assertEquals("CS_summary", CrossSections.safeName("summary"))
        assertEquals("rioaguasabajo", CrossSections.safeName("río/aguas#abajo"))
    }
}

/** Swift `ResultFilesTests` (same Swift file as `ProjectFormatTests`). */
class ResultFilesTests {
    /** RIVeR's `pisco` example: 11×58 grid, 29 frame pairs, NaN where no vector. */
    @Test
    fun readsAndRewritesDesktopPIVResults() {
        val piv = PIVResults.read(TestData.file("river-examples/pisco/piv_results.json"))
        assertEquals(11, piv.rows)
        assertEquals(58, piv.columns)
        assertEquals(29, piv.pairCount)
        assertEquals(638, piv.x.size)
        assertEquals(29, piv.gradient.size)
        assertTrue(piv.uMedian.any { it.isNaN() })
        assertEquals(48.0, piv.x[0], 0.0)

        val out = File(TestData.tempDirectory(), "piv_results.json")
        piv.write(out)
        val back = PIVResults.read(out)
        assertEquals(piv.rows, back.rows)
        assertEquals(piv.x, back.x)
        assertTrue(back.u.flatten().zip(piv.u.flatten()).all { (a, b) -> a == b || (a.isNaN() && b.isNaN()) })
    }

    @Test
    fun readsDesktopBathymetry() {
        val b = Bathymetry.read(TestData.file("river-examples/pisco/pisco_section.csv"))
        assertEquals(8, b.stations.size)
        assertEquals(0.0, b.stations.first(), 0.0)
        assertEquals(1.765, b.elevations.first(), 0.0)
        assertEquals(22.656012, b.stations.last(), 1e-9)
        assertEquals(0.0, b.minElevation)
    }

    @Test
    fun bathymetryVariants() {
        val semicolon = Bathymetry.parse(csv = "x;y\n10;1,5\n0;2,0\n")
        assertEquals(listOf(0.0, 10.0), semicolon.stations) // reversed to increasing stations
        assertEquals(listOf(2.0, 1.5), semicolon.elevations)
        val feet = Bathymetry.parse(csv = "0,10\n10,0\n", feet = true)
        assertEquals(3.048, feet.stations[1], 1e-12)
        assertTrue(feet.csv.startsWith("station,stage\n"))
        assertThrows(ProjectError::class.java) { Bathymetry.parse(csv = "station,stage\n0,abc\n1,2\n") }
    }

    /** RIVeR's UAV example keeps the similarity form desktop writes: [[a, b, tx], [b, -a, ty], [0, 0, 1]]. */
    @Test
    fun readsDesktopUAVMatrix() {
        val rows = JSON.read(TestData.file("river-examples/yanuncay/transformation.json")).arrayValue!!.mapNotNull { it.doubleArray }
        val m = Matrix3.fromRows(rows)
        assertEquals(-m[1, 1], m[0, 0], 1e-15)
        assertEquals(m[1, 0], m[0, 1], 1e-15)
        assertEquals(listOf(0.0, 0.0, 1.0), m.rows[2])
    }
}
