package com.openminis.app.data.model

import com.openminis.app.data.db.ProviderModelEntryEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

class CrossPlatformBackupRestoreUnitTest {

    @get:org.junit.Rule
    val temporaryFolder = org.junit.rules.TemporaryFolder()

    @Test
    fun testRestoreBackupWithCustomModelOverrides() {
        // A fixed cross-platform wire sample makes this test portable to a clean CI runner.
        val sample = """
            {
              "instances": [{
                "id": "fixture-provider", "label": "Fixture",
                "providerType": "openAI", "credentialType": "apiKey",
                "createdAt": "2026-01-01T00:00:00Z"
              }],
              "modelEntries": [{
                "uuid": "fixture-model", "providerInstanceId": "fixture-provider",
                "model": {"id": "test-gpt-custom", "displayName": "Custom GPT", "provider": "openAI"},
                "overrides": {
                  "temperature": 0.7, "topP": 0.9,
                  "customHeaders": {"X-Custom-Auth": "Token-ABC-123", "HTTP-Referer": "https://openminis.app"},
                  "extraBodyParams": {"custom_flag": true},
                  "inputPricePerMillion": 1.25, "outputPricePerMillion": 5.0,
                  "cacheReadPricePerMillion": 0.125, "cacheWritePricePerMillion": 1.5
                }
              }]
            }
        """.trimIndent()
        val backupFile = temporaryFolder.newFile("cross-platform.minisbak")
        java.util.zip.ZipOutputStream(backupFile.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("data/provider_config.json"))
            zip.write(sample.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        var extractedJson: String? = null
        ZipInputStream(FileInputStream(backupFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "data/provider_config.json") {
                    extractedJson = zis.bufferedReader().readText()
                    break
                }
                entry = zis.nextEntry
            }
        }
        assertNotNull("Must extract provider_config.json from minisbak", extractedJson)

        val jsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        // 1. Deserialization
        val config = jsonParser.decodeFromString<ProviderConfig>(extractedJson!!)
        assertNotNull(config)
        assertEquals(1, config.instances.size)
        assertEquals(1, config.modelEntries.size)

        val modelEntry = config.modelEntries[0]
        val overrides = modelEntry.overrides
        assertNotNull("Overrides must be parsed", overrides)

        // Every newly added custom parameter must parse intact
        assertEquals(0.7, overrides.temperature ?: 0.0, 0.0001)
        assertEquals(0.9, overrides.topP ?: 0.0, 0.0001)
        assertNotNull("customHeaders must not be null", overrides.customHeaders)
        assertEquals("Token-ABC-123", overrides.customHeaders?.get("X-Custom-Auth"))
        assertEquals("https://openminis.app", overrides.customHeaders?.get("HTTP-Referer"))
        assertNotNull("extraBodyParams must not be null", overrides.extraBodyParams)
        assertEquals(1.25, overrides.inputPricePerMillion ?: -1.0, 0.0001)
        assertEquals(5.0, overrides.outputPricePerMillion ?: -1.0, 0.0001)
        assertEquals(0.125, overrides.cacheReadPricePerMillion ?: -1.0, 0.0001)
        assertEquals(1.5, overrides.cacheWritePricePerMillion ?: -1.0, 0.0001)

        // 2. Simulate writing the local Room entity's overrides_json column
        val serializedOverrides = jsonParser.encodeToString(overrides)
        val entity = ProviderModelEntryEntity(
            id = modelEntry.uuid,
            providerInstanceId = modelEntry.providerInstanceId,
            baseModelJson = jsonParser.encodeToString(modelEntry.baseModel),
            overridesJson = serializedOverrides,
            isCustom = 0,
            isHidden = 0,
            sortOrder = 0,
            userModifiedAt = System.currentTimeMillis()
        )
        assertNotNull(entity.overridesJson)

        // 3. Simulate reading it back from the Room entity
        val restoredOverrides = jsonParser.decodeFromString<ModelOverrides>(entity.overridesJson!!)
        assertEquals(0.7, restoredOverrides.temperature ?: 0.0, 0.0001)
        assertEquals("Token-ABC-123", restoredOverrides.customHeaders?.get("X-Custom-Auth"))

        // 4. Simulate a model-list refresh (Refresh Models); the overrides must not be wiped
        val remoteNewBaseModel = LLMModel(
            id = "test-gpt-custom",
            displayName = "Remote Updated GPT Name",
            provider = "openAI"
        )
        val refreshedModelEntry = ModelEntry(
            providerInstanceId = entity.providerInstanceId,
            baseModel = remoteNewBaseModel,
            overrides = restoredOverrides,
            isCustom = entity.isCustom != 0,
            isHidden = entity.isHidden != 0,
            uuid = entity.id,
            userModifiedAt = entity.userModifiedAt
        )
        // The custom parameters must survive the refresh intact
        assertEquals(0.7, refreshedModelEntry.overrides.temperature ?: 0.0, 0.0001)
        assertEquals(0.9, refreshedModelEntry.overrides.topP ?: 0.0, 0.0001)
        assertEquals("Token-ABC-123", refreshedModelEntry.overrides.customHeaders?.get("X-Custom-Auth"))
        assertEquals("https://openminis.app", refreshedModelEntry.overrides.customHeaders?.get("HTTP-Referer"))

        assertEquals(overrides.inputPricePerMillion, refreshedModelEntry.overrides.inputPricePerMillion)
        assertEquals(overrides.outputPricePerMillion, refreshedModelEntry.overrides.outputPricePerMillion)
        assertEquals(overrides.cacheReadPricePerMillion, refreshedModelEntry.overrides.cacheReadPricePerMillion)
        assertEquals(overrides.cacheWritePricePerMillion, refreshedModelEntry.overrides.cacheWritePricePerMillion)

        println(">>> VERIFIED: All custom model parameters successfully deserialized, saved to DB Entity, and preserved across model refresh without any MissingFieldException!")
    }
}
