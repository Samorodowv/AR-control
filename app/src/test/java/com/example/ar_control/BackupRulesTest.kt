package com.example.ar_control

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class BackupRulesTest {

    @Test
    fun dataExtractionRulesExcludeDownloadedGemmaModelAndPreferencesFromCloudBackupAndDeviceTransfer() {
        val document = parseXml(resourceFile("data_extraction_rules.xml"))

        assertExcludes(document.documentElement, "cloud-backup", "file", "models/")
        assertExcludes(document.documentElement, "cloud-backup", "sharedpref", "gemma_subtitle_prefs.xml")
        assertExcludes(document.documentElement, "device-transfer", "file", "models/")
        assertExcludes(document.documentElement, "device-transfer", "sharedpref", "gemma_subtitle_prefs.xml")
    }

    @Test
    fun backupRulesExcludeDownloadedGemmaModelAndPreferences() {
        val document = parseXml(resourceFile("backup_rules.xml"))
        val root = document.documentElement

        assertTrue(
            "Expected full-backup-content root",
            root.tagName == "full-backup-content"
        )
        assertTrue(root.hasExclude("file", "models/"))
        assertTrue(root.hasExclude("sharedpref", "gemma_subtitle_prefs.xml"))
    }

    private fun assertExcludes(root: Element, sectionName: String, domain: String, path: String) {
        val sections = root.getElementsByTagName(sectionName)
        assertTrue("Expected <$sectionName> section", sections.length > 0)
        val section = sections.item(0) as Element

        assertTrue("Expected <$sectionName> to exclude $domain:$path", section.hasExclude(domain, path))
    }

    private fun Element.hasExclude(domain: String, path: String): Boolean {
        val excludes = getElementsByTagName("exclude")
        return (0 until excludes.length)
            .map { excludes.item(it) as Element }
            .any { element ->
                element.getAttribute("domain") == domain && element.getAttribute("path") == path
            }
    }

    private fun parseXml(file: File) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(file)

    private fun resourceFile(name: String): File {
        return listOf(
            File("app/src/main/res/xml/$name"),
            File("src/main/res/xml/$name")
        ).firstOrNull { it.isFile } ?: error("Could not locate $name")
    }
}
