package com.techlion.healthconnectexporter

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Lossless-enough exporter for common Health Connect health and fitness record types.
 * Each output line is an independent JSON object so large histories can be streamed safely.
 */
class ExportWorker(context: Context) {
    private val appContext = context.applicationContext
    private val client = HealthConnectClient.getOrCreate(appContext)
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    val permissions: Set<String>
        get() = buildSet {
            supportedRecordTypes.forEach { add(HealthPermission.getReadPermission(it)) }
            if (client.features.getFeatureStatus(
                    HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY
                ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            ) {
                add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
            }
        }

    suspend fun grantedPermissions(): Set<String> =
        client.permissionController.getGrantedPermissions()

    suspend fun hasAllPermissions(): Boolean =
        grantedPermissions().containsAll(permissions)

    fun exportDirectory(): File = File(appContext.filesDir, EXPORT_DIRECTORY)

    fun hasExport(): Boolean = exportDirectory().listFiles()?.any { file ->
        file.isFile && (file.extension == "ndjson" || file.extension == "json")
    } == true

    fun createShareArchive(): File {
        val sourceFiles = exportDirectory().listFiles()
            ?.filter { it.isFile && (it.extension == "ndjson" || it.extension == "json") }
            ?.sortedBy { it.name }
            .orEmpty()
        require(sourceFiles.isNotEmpty()) { "No completed export was found" }

        val shareDirectory = File(appContext.cacheDir, SHARE_DIRECTORY).apply { mkdirs() }
        shareDirectory.listFiles()?.forEach { file ->
            if (file.isFile && file.extension == "zip") file.delete()
        }
        val timestamp = ARCHIVE_TIMESTAMP.format(Instant.now())
        val archive = File(shareDirectory, "health-connect-export-$timestamp.zip")

        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            sourceFiles.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().buffered().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return archive
    }

    suspend fun exportAll(onProgress: (String, Int) -> Unit): ExportResult =
        withContext(Dispatchers.IO) {
            val directory = exportDirectory().apply { mkdirs() }
            directory.listFiles()?.forEach { file ->
                if (file.isFile && (file.extension == "ndjson" || file.extension == "json")) {
                    file.delete()
                }
            }

            val startedAt = Instant.now()
            writeStatus("running", startedAt, null, null)
            val counts = linkedMapOf<String, Int>()
            val errors = linkedMapOf<String, String>()

            suspend fun run(name: String, block: suspend () -> Int) {
                try {
                    val count = block()
                    counts[name] = count
                    onProgress(name, count)
                } catch (error: Exception) {
                    counts[name] = 0
                    errors[name] = "${error::class.java.simpleName}: ${error.message ?: "unknown error"}"
                    onProgress(name, 0)
                }
            }

            run("heart_rate") {
                readAndWrite<HeartRateRecord>("heart_rate") { record ->
                    intervalBase("HeartRateRecord", record, record.startTime, record.endTime,
                        record.startZoneOffset?.toString(), record.endZoneOffset?.toString()).apply {
                        add("samples", JsonArray().also { samples ->
                            record.samples.forEach { sample ->
                                samples.add(JsonObject().apply {
                                    addProperty("time", sample.time.toString())
                                    addProperty("beatsPerMinute", sample.beatsPerMinute)
                                })
                            }
                        })
                    }
                }
            }
            run("resting_heart_rate") {
                readAndWrite<RestingHeartRateRecord>("resting_heart_rate") { record ->
                    instantBase("RestingHeartRateRecord", record, record.time,
                        record.zoneOffset?.toString()).apply {
                        addProperty("beatsPerMinute", record.beatsPerMinute)
                    }
                }
            }
            run("heart_rate_variability_rmssd") {
                readAndWrite<HeartRateVariabilityRmssdRecord>("heart_rate_variability_rmssd") { record ->
                    instantBase("HeartRateVariabilityRmssdRecord", record, record.time,
                        record.zoneOffset?.toString()).apply {
                        addProperty("heartRateVariabilityMillis", record.heartRateVariabilityMillis)
                    }
                }
            }
            run("sleep_session") {
                readAndWrite<SleepSessionRecord>("sleep_session") { record ->
                    intervalBase("SleepSessionRecord", record, record.startTime, record.endTime,
                        record.startZoneOffset?.toString(), record.endZoneOffset?.toString()).apply {
                        addNullable("title", record.title)
                        addNullable("notes", record.notes)
                        add("stages", JsonArray().also { stages ->
                            record.stages.forEach { stage ->
                                stages.add(JsonObject().apply {
                                    addProperty("startTime", stage.startTime.toString())
                                    addProperty("endTime", stage.endTime.toString())
                                    addProperty("stage", stage.stage)
                                    addProperty("stageName", sleepStageName(stage.stage))
                                })
                            }
                        })
                    }
                }
            }
            run("oxygen_saturation") {
                readAndWrite<OxygenSaturationRecord>("oxygen_saturation") { record ->
                    instantBase("OxygenSaturationRecord", record, record.time,
                        record.zoneOffset?.toString()).apply {
                        addProperty("percentage", record.percentage.value)
                    }
                }
            }
            run("respiratory_rate") {
                readAndWrite<RespiratoryRateRecord>("respiratory_rate") { record ->
                    instantBase("RespiratoryRateRecord", record, record.time,
                        record.zoneOffset?.toString()).apply {
                        addProperty("ratePerMinute", record.rate)
                    }
                }
            }
            run("weight") {
                readAndWrite<WeightRecord>("weight") { record ->
                    instantBase("WeightRecord", record, record.time,
                        record.zoneOffset?.toString()).apply {
                        addProperty("kilograms", record.weight.inKilograms)
                    }
                }
            }
            run("height") {
                readAndWrite<HeightRecord>("height") { record ->
                    instantBase("HeightRecord", record, record.time,
                        record.zoneOffset?.toString()).apply {
                        addProperty("meters", record.height.inMeters)
                    }
                }
            }
            run("steps") {
                readAndWrite<StepsRecord>("steps") { record ->
                    intervalBase("StepsRecord", record, record.startTime, record.endTime,
                        record.startZoneOffset?.toString(), record.endZoneOffset?.toString()).apply {
                        addProperty("count", record.count)
                    }
                }
            }
            run("distance") {
                readAndWrite<DistanceRecord>("distance") { record ->
                    intervalBase("DistanceRecord", record, record.startTime, record.endTime,
                        record.startZoneOffset?.toString(), record.endZoneOffset?.toString()).apply {
                        addProperty("meters", record.distance.inMeters)
                    }
                }
            }
            run("active_calories") {
                readAndWrite<ActiveCaloriesBurnedRecord>("active_calories") { record ->
                    intervalBase("ActiveCaloriesBurnedRecord", record, record.startTime, record.endTime,
                        record.startZoneOffset?.toString(), record.endZoneOffset?.toString()).apply {
                        addProperty("kilocalories", record.energy.inKilocalories)
                    }
                }
            }
            run("total_calories") {
                readAndWrite<TotalCaloriesBurnedRecord>("total_calories") { record ->
                    intervalBase("TotalCaloriesBurnedRecord", record, record.startTime, record.endTime,
                        record.startZoneOffset?.toString(), record.endZoneOffset?.toString()).apply {
                        addProperty("kilocalories", record.energy.inKilocalories)
                    }
                }
            }
            run("vo2_max") {
                readAndWrite<Vo2MaxRecord>("vo2_max") { record ->
                    instantBase("Vo2MaxRecord", record, record.time,
                        record.zoneOffset?.toString()).apply {
                        addProperty("millilitersPerMinuteKilogram", record.vo2MillilitersPerMinuteKilogram)
                        addProperty("measurementMethod", record.measurementMethod)
                    }
                }
            }
            run("exercise_session") {
                readAndWrite<ExerciseSessionRecord>("exercise_session") { record ->
                    intervalBase("ExerciseSessionRecord", record, record.startTime, record.endTime,
                        record.startZoneOffset?.toString(), record.endZoneOffset?.toString()).apply {
                        addProperty("exerciseType", record.exerciseType)
                        addNullable("title", record.title)
                        addNullable("notes", record.notes)
                        add("segments", JsonArray().also { segments ->
                            record.segments.forEach { segment ->
                                segments.add(JsonObject().apply {
                                    addProperty("startTime", segment.startTime.toString())
                                    addProperty("endTime", segment.endTime.toString())
                                    addProperty("segmentType", segment.segmentType)
                                    addProperty("repetitions", segment.repetitions)
                                })
                            }
                        })
                        add("laps", JsonArray().also { laps ->
                            record.laps.forEach { lap ->
                                laps.add(JsonObject().apply {
                                    addProperty("startTime", lap.startTime.toString())
                                    addProperty("endTime", lap.endTime.toString())
                                    lap.length?.let { addProperty("lengthMeters", it.inMeters) }
                                })
                            }
                        })
                        addProperty("exerciseRouteResult", record.exerciseRouteResult::class.java.simpleName)
                    }
                }
            }

            val finishedAt = Instant.now()
            val manifest = JsonObject().apply {
                addProperty("schemaVersion", SCHEMA_VERSION)
                addProperty("startedAt", startedAt.toString())
                addProperty("finishedAt", finishedAt.toString())
                addProperty("totalRecords", counts.values.sum())
                add("recordCounts", gson.toJsonTree(counts))
                add("errors", gson.toJsonTree(errors))
                add("grantedPermissions", gson.toJsonTree(grantedPermissions().sorted()))
            }
            File(directory, "manifest.json").writeText(gson.toJson(manifest))
            val result = ExportResult(counts.values.sum(), counts, errors, directory)
            writeStatus("complete", startedAt, finishedAt, result)
            result
        }

    private suspend inline fun <reified T : Record> readAndWrite(
        fileName: String,
        crossinline toJson: (T) -> JsonObject
    ): Int {
        val output = File(exportDirectory(), "$fileName.ndjson")
        var pageToken: String? = null
        var count = 0
        output.bufferedWriter().use { writer ->
            do {
                val response = client.readRecords(
                    ReadRecordsRequest(
                        recordType = T::class,
                        timeRangeFilter = TimeRangeFilter.after(Instant.EPOCH),
                        pageSize = 1000,
                        pageToken = pageToken
                    )
                )
                response.records.forEach { record ->
                    writer.write(gson.toJson(toJson(record)))
                    writer.newLine()
                    count++
                }
                writer.flush()
                pageToken = response.pageToken
            } while (pageToken != null)
        }
        return count
    }

    private fun intervalBase(
        type: String,
        record: Record,
        start: Instant,
        end: Instant,
        startZoneOffset: String?,
        endZoneOffset: String?
    ): JsonObject = base(type, record.metadata).apply {
        addProperty("startTime", start.toString())
        addProperty("endTime", end.toString())
        addNullable("startZoneOffset", startZoneOffset)
        addNullable("endZoneOffset", endZoneOffset)
    }

    private fun instantBase(
        type: String,
        record: Record,
        time: Instant,
        zoneOffset: String?
    ): JsonObject = base(type, record.metadata).apply {
        addProperty("time", time.toString())
        addNullable("zoneOffset", zoneOffset)
    }

    private fun base(type: String, metadata: Metadata): JsonObject = JsonObject().apply {
        addProperty("schemaVersion", SCHEMA_VERSION)
        addProperty("recordType", type)
        add("metadata", JsonObject().apply {
            addProperty("id", metadata.id)
            addProperty("dataOriginPackage", metadata.dataOrigin.packageName)
            addProperty("lastModifiedTime", metadata.lastModifiedTime.toString())
            addNullable("clientRecordId", metadata.clientRecordId)
            addProperty("clientRecordVersion", metadata.clientRecordVersion)
            addProperty("recordingMethod", metadata.recordingMethod)
            metadata.device?.let { device ->
                add("device", JsonObject().apply {
                    addProperty("type", device.type)
                    addNullable("manufacturer", device.manufacturer)
                    addNullable("model", device.model)
                })
            }
        })
    }

    private fun JsonObject.addNullable(name: String, value: String?) {
        if (value == null) add(name, null) else addProperty(name, value)
    }

    private fun sleepStageName(stage: Int): String = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> "AWAKE"
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> "SLEEPING"
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "OUT_OF_BED"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "LIGHT"
        SleepSessionRecord.STAGE_TYPE_DEEP -> "DEEP"
        SleepSessionRecord.STAGE_TYPE_REM -> "REM"
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "AWAKE_IN_BED"
        else -> "UNKNOWN"
    }

    private fun writeStatus(
        state: String,
        startedAt: Instant,
        finishedAt: Instant?,
        result: ExportResult?
    ) {
        val status = JsonObject().apply {
            addProperty("state", state)
            addProperty("startedAt", startedAt.toString())
            finishedAt?.let { addProperty("finishedAt", it.toString()) }
            result?.let {
                addProperty("totalRecords", it.totalRecords)
                add("recordCounts", gson.toJsonTree(it.recordCounts))
                add("errors", gson.toJsonTree(it.errors))
            }
        }
        File(exportDirectory(), "status.json").writeText(gson.toJson(status))
    }

    data class ExportResult(
        val totalRecords: Int,
        val recordCounts: Map<String, Int>,
        val errors: Map<String, String>,
        val directory: File
    )

    companion object {
        private const val EXPORT_DIRECTORY = "health_export"
        private const val SHARE_DIRECTORY = "shared_exports"
        private const val SCHEMA_VERSION = "1.0"
        private val ARCHIVE_TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC)

        private val supportedRecordTypes = listOf(
            HeartRateRecord::class,
            RestingHeartRateRecord::class,
            HeartRateVariabilityRmssdRecord::class,
            SleepSessionRecord::class,
            OxygenSaturationRecord::class,
            RespiratoryRateRecord::class,
            WeightRecord::class,
            HeightRecord::class,
            StepsRecord::class,
            DistanceRecord::class,
            ActiveCaloriesBurnedRecord::class,
            TotalCaloriesBurnedRecord::class,
            Vo2MaxRecord::class,
            ExerciseSessionRecord::class
        )
    }
}
