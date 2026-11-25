package org.rivem.core

import java.io.File
import java.nio.file.Files

/** Shared fixtures in `<repo>/ci/testdata` (the same ones the Swift core tests use). */
object TestData {
    val root: File by lazy {
        System.getProperty("rivem.testdata")?.let { File(it) }
            ?: generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "ci/testdata") }
                .first { it.isDirectory }
    }

    fun file(relative: String): File = File(root, relative)

    /** Fresh temporary directory. */
    fun tempDirectory(): File = Files.createTempDirectory("rivem-tests-").toFile()

    /** Copies a fixture project folder to a temporary location and adds the empty `frames/` git cannot store. */
    fun projectCopy(relative: String): File {
        val src = file(relative)
        val dest = File(tempDirectory(), src.name)
        src.copyRecursively(dest)
        File(dest, "frames").mkdirs()
        return dest
    }
}
