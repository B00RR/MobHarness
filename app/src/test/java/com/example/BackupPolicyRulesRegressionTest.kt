package com.example

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BackupPolicyRulesRegressionTest {

    private val projectRoot = File(System.getProperty("user.dir") ?: ".")
    private val appDir = if (File(projectRoot, "app").exists()) File(projectRoot, "app") else projectRoot

    @Test
    fun `data_extraction_rules excludes sensitive data from cloud and models from transfer`() {
        val rulesFile = File(appDir, "src/main/res/xml/data_extraction_rules.xml")
        assertTrue("data_extraction_rules.xml must exist at ${rulesFile.absolutePath}", rulesFile.exists())

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rulesFile)
        val cloudBackupNodes = doc.getElementsByTagName("cloud-backup")
        assertTrue("<cloud-backup> section must be present", cloudBackupNodes.length > 0)

        val cloudBackupEl = cloudBackupNodes.item(0) as Element
        val cloudExcludes = cloudBackupEl.getElementsByTagName("exclude")
        val cloudExcludePairs = mutableSetOf<Pair<String, String>>()
        for (i in 0 until cloudExcludes.length) {
            val el = cloudExcludes.item(i) as Element
            cloudExcludePairs.add(el.getAttribute("domain") to el.getAttribute("path"))
        }

        // Verify cloud exclusions
        assertTrue("Cloud backup must exclude databases",
            cloudExcludePairs.any { it.first == "database" && (it.second == "." || it.second.contains("mobharn")) })
        assertTrue("Cloud backup must exclude vault files",
            cloudExcludePairs.any { it.first == "file" && it.second == "mobharn_vault" })
        assertTrue("Cloud backup must exclude coding workspace",
            cloudExcludePairs.any { it.first == "file" && it.second == "workspace" })
        assertTrue("Cloud backup must exclude replaceable model weights",
            cloudExcludePairs.any { it.first == "file" && it.second == "litertlm_models" })

        // Verify device transfer exclusions
        val deviceTransferNodes = doc.getElementsByTagName("device-transfer")
        assertTrue("<device-transfer> section must be present", deviceTransferNodes.length > 0)
        val deviceTransferEl = deviceTransferNodes.item(0) as Element
        val transferExcludes = deviceTransferEl.getElementsByTagName("exclude")
        val transferExcludePairs = mutableSetOf<Pair<String, String>>()
        for (i in 0 until transferExcludes.length) {
            val el = transferExcludes.item(i) as Element
            transferExcludePairs.add(el.getAttribute("domain") to el.getAttribute("path"))
        }

        assertTrue("Device transfer must exclude multi-GB replaceable model weights",
            transferExcludePairs.any { it.first == "file" && it.second == "litertlm_models" })
        assertFalse("Device transfer must NOT exclude databases (so user chats/memories transfer)",
            transferExcludePairs.any { it.first == "database" })
        assertFalse("Device transfer must NOT exclude vault files",
            transferExcludePairs.any { it.first == "file" && it.second == "mobharn_vault" })
    }

    @Test
    fun `backup_rules excludes sensitive data and replaceable models for legacy backup`() {
        val rulesFile = File(appDir, "src/main/res/xml/backup_rules.xml")
        assertTrue("backup_rules.xml must exist at ${rulesFile.absolutePath}", rulesFile.exists())

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rulesFile)
        val rootNodes = doc.getElementsByTagName("full-backup-content")
        assertTrue("<full-backup-content> must be present", rootNodes.length > 0)

        val rootEl = rootNodes.item(0) as Element
        val excludes = rootEl.getElementsByTagName("exclude")
        val excludePairs = mutableSetOf<Pair<String, String>>()
        for (i in 0 until excludes.length) {
            val el = excludes.item(i) as Element
            excludePairs.add(el.getAttribute("domain") to el.getAttribute("path"))
        }

        assertTrue("Legacy backup must exclude databases",
            excludePairs.any { it.first == "database" })
        assertTrue("Legacy backup must exclude vault files",
            excludePairs.any { it.first == "file" && it.second == "mobharn_vault" })
        assertTrue("Legacy backup must exclude coding workspace",
            excludePairs.any { it.first == "file" && it.second == "workspace" })
        assertTrue("Legacy backup must exclude replaceable model weights",
            excludePairs.any { it.first == "file" && it.second == "litertlm_models" })
    }

    @Test
    fun `backup documentation exists in docs backup md and not README`() {
        val backupDoc = File(projectRoot, "docs/backup.md")
        assertTrue("docs/backup.md must exist", backupDoc.exists())
        val content = backupDoc.readText()

        assertTrue("Must document local inference vs storage", content.contains("Local Inference", ignoreCase = true))
        assertTrue("Must document cloud backup policy", content.contains("Cloud Backup", ignoreCase = true))
        assertTrue("Must document device-to-device transfer", content.contains("Device Transfer", ignoreCase = true) || content.contains("Device-to-Device", ignoreCase = true))
        assertTrue("Must document restore recovery expectations", content.contains("Restore", ignoreCase = true))

        val readmeFile = File(projectRoot, "README.md")
        if (readmeFile.exists()) {
            val readmeContent = readmeFile.readText()
            assertFalse(
                "Backup policy details must be in docs/backup.md, not README.md",
                readmeContent.contains("<data-extraction-rules>") || readmeContent.contains("<cloud-backup>")
            )
        }
    }
}
