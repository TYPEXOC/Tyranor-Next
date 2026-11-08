package com.tyranor.next.core.engine.external

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RpgMakerRuntimeEnvironmentTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun recognizesTheThreeRtpGenerations() {
        listOf(
            "rpgmxp" to "xp",
            "rpgmvx" to "vx",
            "rpgmvxace" to "vxace",
        ).forEach { (gameType, fixture) ->
            val root = temporaryFolder.newFolder(fixture)
            createRtpContent(root, fixture)

            assertNull(RpgMakerRuntimeEnvironment.validateRtpContent(root, gameType))
        }
    }

    @Test
    fun rejectsInstallerOnlyAndUnrelatedArchives() {
        val installerOnly = temporaryFolder.newFolder("installer")
        writeFile(installerOnly, "setup.exe")
        writeFile(installerOnly, "readme.txt")

        assertEquals(
            RtpImportRejection.InvalidRtpPackage,
            RpgMakerRuntimeEnvironment.validateRtpContent(installerOnly, "rpgmvx"),
        )

        val unrelated = temporaryFolder.newFolder("unrelated")
        writeFile(unrelated, "Audio/BGM/theme.ogg")
        writeFile(unrelated, "Graphics/title.png")

        assertEquals(
            RtpImportRejection.InvalidRtpPackage,
            RpgMakerRuntimeEnvironment.validateRtpContent(unrelated, "rpgmvx"),
        )
    }

    @Test
    fun rejectsAValidRtpForTheWrongSelectedGeneration() {
        val xp = temporaryFolder.newFolder("wrong-generation")
        createRtpContent(xp, "xp")

        assertEquals(
            RtpImportRejection.InvalidRtpPackage,
            RpgMakerRuntimeEnvironment.validateRtpContent(xp, "rpgmvxace"),
        )
    }

    @Test
    fun invalidPackageDoesNotReplacePreviouslyImportedRtp() {
        val temp = temporaryFolder.newFolder("import")
        writeFile(temp, "setup.exe")
        val target = temporaryFolder.newFolder("target")
        val existing = writeFile(target, "Graphics/Characters/keep.png")

        val result = RpgMakerRuntimeEnvironment.commitImportTemp(temp, target, "rpgmvx")

        assertEquals(RtpImportRejection.InvalidRtpPackage, result)
        assertTrue(existing.isFile)
        assertEquals("existing", existing.readText())
        assertFalse(File(target, "setup.exe").exists())
    }

    @Test
    fun acceptsOfficialStyleSingleWrapperDirectory() {
        val temp = temporaryFolder.newFolder("wrapped-import")
        createRtpContent(File(temp, "RPGVXAce_RTP100"), "vxace")
        val targetParent = temporaryFolder.newFolder("rtp")
        val target = File(targetParent, "app")

        assertNull(RpgMakerRuntimeEnvironment.commitImportTemp(temp, target, "rpgmvxace"))
        assertTrue(File(target, "Graphics/Battlebacks1/sample.png").isFile)
        assertFalse(File(target, "RPGVXAce_RTP100").exists())
    }

    private fun createRtpContent(root: File, generation: String) {
        writeFile(root, "Game.ico")
        writeFile(root, "Audio/BGM/sample.ogg")
        writeFile(root, "Graphics/Characters/sample.png")

        when (generation) {
            "xp" -> {
                listOf(
                    "Autotiles",
                    "Windowskins",
                    "Transitions",
                    "Battlebacks",
                ).forEach { writeFile(root, "Graphics/$it/sample.png") }
            }

            "vx" -> {
                listOf("TileA1", "TileA2", "TileA3", "TileA4", "TileA5", "TileB", "TileC")
                    .forEach { writeFile(root, "Graphics/System/$it.png") }
                writeFile(root, "Graphics/Faces/Actor1.png")
            }

            "vxace" -> {
                listOf("Battlebacks1", "Battlebacks2", "Tilesets", "Titles1", "Titles2")
                    .forEach { writeFile(root, "Graphics/$it/sample.png") }
            }
        }
    }

    private fun writeFile(root: File, relativePath: String): File {
        val file = File(root, relativePath)
        file.parentFile?.mkdirs()
        when (relativePath) {
            "Game.ico" -> file.writeBytes(byteArrayOf(0, 0, 1, 0))
            "Graphics/Characters/keep.png" -> file.writeText("existing")
            else -> file.writeText("fixture")
        }
        return file
    }
}
