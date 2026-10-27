/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.scheduler

import android.content.Context
import android.net.Uri
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.CustomDomainRepository
import com.celzero.bravedns.database.CustomIpRepository
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_SCHEDULER
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class PersistentJobInfo(
    val id: String = UUID.randomUUID().toString(),
    val command: String,
    val data: String,
    val file: String,
    val executeAt: String,
    val days: String,
    val isRecurring: Boolean,
    val rawJson: String,
    var lastExecutedTime: Long = 0L
)

object AutomationManager : KoinComponent {

    private const val PREFS_NAME = "RethinkPrefs"
    private const val KEY_PERSISTENT_JOBS = "persistent_automation_jobs"
    private const val KEY_ACTIVITY_LOGS = "activity_logs_json"
    private const val KEY_AUTHORIZED_FILES = "authorized_files_map"

    private val persistentState by inject<PersistentState>()
    private val customIpRepository by inject<CustomIpRepository>()
    private val customDomainRepository by inject<CustomDomainRepository>()
    private val connectionTrackerRepository by inject<ConnectionTrackerRepository>()

    private val automationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeJobCoroutines = ConcurrentHashMap<String, Job>()
    private val isCatchUpRunning = AtomicBoolean(false)

    @Volatile
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        Logger.i(LOG_TAG_SCHEDULER, "Initializing AutomationManager")
        automationScope.launch {
            checkAndRunCatchUp(context.applicationContext)
        }
    }

    // ------------------------------------------------------------------------
    // Job Persistence
    // ------------------------------------------------------------------------

    @Synchronized
    fun getPersistentJobs(context: Context): MutableList<PersistentJobInfo> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_PERSISTENT_JOBS, "[]") ?: "[]"
        val list = mutableListOf<PersistentJobInfo>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    PersistentJobInfo(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        command = obj.optString("command", ""),
                        data = obj.optString("data", ""),
                        file = obj.optString("file", ""),
                        executeAt = obj.optString("executeAt", ""),
                        days = obj.optString("days", "ALL"),
                        isRecurring = obj.optBoolean("isRecurring", false),
                        rawJson = obj.optString("rawJson", "{}"),
                        lastExecutedTime = obj.optLong("lastExecutedTime", 0L)
                    )
                )
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_SCHEDULER, "Error reading persistent jobs: ${e.message}", e)
        }
        return list
    }

    @Synchronized
    fun savePersistentJobs(context: Context, jobs: List<PersistentJobInfo>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        for (job in jobs) {
            val obj = JSONObject().apply {
                put("id", job.id)
                put("command", job.command)
                put("data", job.data)
                put("file", job.file)
                put("executeAt", job.executeAt)
                put("days", job.days)
                put("isRecurring", job.isRecurring)
                put("rawJson", job.rawJson)
                put("lastExecutedTime", job.lastExecutedTime)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_PERSISTENT_JOBS, array.toString()).apply()
        Logger.i(LOG_TAG_SCHEDULER, "Saved ${jobs.size} persistent jobs")
    }

    @Synchronized
    fun updateJobExecutionTime(context: Context, jobId: String, executedTime: Long) {
        val jobs = getPersistentJobs(context)
        val matched = jobs.find { it.id == jobId }
        if (matched != null) {
            matched.lastExecutedTime = executedTime
            savePersistentJobs(context, jobs)
        }
    }

    @Synchronized
    fun removeJob(context: Context, jobId: String) {
        activeJobCoroutines[jobId]?.cancel()
        activeJobCoroutines.remove(jobId)

        val jobs = getPersistentJobs(context)
        val updated = jobs.filter { it.id != jobId }
        savePersistentJobs(context, updated)
        Logger.i(LOG_TAG_SCHEDULER, "Removed job $jobId, remaining: ${updated.size}")
    }

    @Synchronized
    fun clearAllJobs(context: Context) {
        activeJobCoroutines.values.forEach { it.cancel() }
        activeJobCoroutines.clear()
        savePersistentJobs(context, emptyList())
        Logger.i(LOG_TAG_SCHEDULER, "All persistent scheduled jobs cleared")
    }

    // ------------------------------------------------------------------------
    // Catch-up & Scheduling Engine
    // ------------------------------------------------------------------------

    suspend fun checkAndRunCatchUp(context: Context) = withContext(Dispatchers.IO) {
        if (!isCatchUpRunning.compareAndSet(false, true)) {
            Logger.i(LOG_TAG_SCHEDULER, "Catch-up is already running, skipping duplicate invocation")
            return@withContext
        }
        try {
            val jobs = getPersistentJobs(context)
            if (jobs.isEmpty()) return@withContext

            val now = System.currentTimeMillis()
            val cal = Calendar.getInstance()
            val currentDay = cal.get(Calendar.DAY_OF_WEEK)

            Logger.i(LOG_TAG_SCHEDULER, "Checking catch-up for ${jobs.size} jobs at $now")

            for (job in jobs) {
                try {
                    val cmdObj = JSONObject(job.rawJson)
                    val (allowedDays, _) = parseDays(cmdObj)
                    if (allowedDays != null && !allowedDays.contains(currentDay)) {
                        continue
                    }

                    val parsedTod = parseTimeOfDay(job.executeAt)
                    if (parsedTod != null) {
                        val (h, m) = parsedTod
                        val targetCal = Calendar.getInstance().apply {
                            timeInMillis = now
                            set(Calendar.HOUR_OF_DAY, h)
                            set(Calendar.MINUTE, m)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        val scheduledTimeToday = targetCal.timeInMillis

                        // Has the scheduled time arrived or passed today?
                        if (now >= scheduledTimeToday) {
                            // Did it already run today for this slot?
                            if (job.lastExecutedTime < scheduledTimeToday) {
                                val isCatchUp = (now - scheduledTimeToday) > 60_000L
                                Logger.i(
                                    LOG_TAG_SCHEDULER,
                                    "Catch-up triggered for ${job.command} ${job.data} (scheduled: ${job.executeAt}, now: $now)"
                                )
                                val success = executeSingleCommand(
                                    context,
                                    cmdObj,
                                    isCatchUp = isCatchUp,
                                    scheduledAt = job.executeAt
                                )
                                job.lastExecutedTime = System.currentTimeMillis()
                                updateJobExecutionTime(context, job.id, job.lastExecutedTime)

                                if (!job.isRecurring) {
                                    removeJob(context, job.id)
                                }
                            }
                        }
                    } else {
                        val fullExecuteTime = parseFullDateTime(job.executeAt)
                        if (fullExecuteTime != null) {
                            if (now >= fullExecuteTime) {
                                if (job.lastExecutedTime == 0L) {
                                    val isCatchUp = (now - fullExecuteTime) > 60_000L
                                    Logger.i(
                                        LOG_TAG_SCHEDULER,
                                        "Catch-up triggered for one-time job ${job.command} ${job.data} (scheduled: ${job.executeAt}, now: $now)"
                                    )
                                    executeSingleCommand(
                                        context,
                                        cmdObj,
                                        isCatchUp = isCatchUp,
                                        scheduledAt = job.executeAt
                                    )
                                    job.lastExecutedTime = System.currentTimeMillis()
                                    updateJobExecutionTime(context, job.id, job.lastExecutedTime)
                                }
                                removeJob(context, job.id)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_SCHEDULER, "Error during catch-up evaluation for job ${job.id}: ${e.message}", e)
                }
            }
        } finally {
            isCatchUpRunning.set(false)
        }

        startScheduler(context)
    }

    fun startScheduler(context: Context) {
        val jobs = getPersistentJobs(context)
        val now = System.currentTimeMillis()

        for (job in jobs) {
            if (activeJobCoroutines[job.id]?.isActive == true) {
                continue
            }

            try {
                val cmdObj = JSONObject(job.rawJson)
                val parsedTod = parseTimeOfDay(job.executeAt)
                if (parsedTod != null) {
                    val (h, m) = parsedTod
                    val targetCal = Calendar.getInstance().apply {
                        timeInMillis = now
                        set(Calendar.HOUR_OF_DAY, h)
                        set(Calendar.MINUTE, m)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    if (targetCal.timeInMillis <= now || job.lastExecutedTime >= targetCal.timeInMillis) {
                        targetCal.add(Calendar.DAY_OF_MONTH, 1)
                    }
                    val initialDelay = maxOf(0L, targetCal.timeInMillis - now)

                    val coroutineJob = automationScope.launch {
                        var nextDelay = initialDelay
                        while (isActive) {
                            delay(nextDelay)
                            val currentCal = Calendar.getInstance()
                            val currentDay = currentCal.get(Calendar.DAY_OF_WEEK)
                            val (allowedDays, daysLabel) = parseDays(cmdObj)

                            if (allowedDays == null || allowedDays.contains(currentDay)) {
                                executeSingleCommand(
                                    context,
                                    cmdObj,
                                    isCatchUp = false,
                                    scheduledAt = job.executeAt
                                )
                                job.lastExecutedTime = System.currentTimeMillis()
                                updateJobExecutionTime(context, job.id, job.lastExecutedTime)
                            } else {
                                val dayName = SimpleDateFormat("EEEE", Locale.getDefault()).format(currentCal.time)
                                logActivity(
                                    context,
                                    "Automated",
                                    "${job.command} ${job.data}".uppercase(),
                                    job.file,
                                    "SKIPPED",
                                    "Today ($dayName) is not in allowed days ($daysLabel)"
                                )
                            }

                            if (!job.isRecurring) {
                                removeJob(context, job.id)
                                break
                            }

                            val nextTarget = Calendar.getInstance().apply {
                                set(Calendar.HOUR_OF_DAY, h)
                                set(Calendar.MINUTE, m)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                                add(Calendar.DAY_OF_MONTH, 1)
                            }
                            nextDelay = maxOf(1000L, nextTarget.timeInMillis - System.currentTimeMillis())
                        }
                    }
                    activeJobCoroutines[job.id] = coroutineJob
                } else {
                    val fullExecuteTime = parseFullDateTime(job.executeAt)
                    if (fullExecuteTime != null && fullExecuteTime > now) {
                        val initialDelay = fullExecuteTime - now
                        val coroutineJob = automationScope.launch {
                            delay(initialDelay)
                            val currentCal = Calendar.getInstance()
                            val currentDay = currentCal.get(Calendar.DAY_OF_WEEK)
                            val (allowedDays, daysLabel) = parseDays(cmdObj)

                            if (allowedDays == null || allowedDays.contains(currentDay)) {
                                executeSingleCommand(
                                    context,
                                    cmdObj,
                                    isCatchUp = false,
                                    scheduledAt = job.executeAt
                                )
                                job.lastExecutedTime = System.currentTimeMillis()
                                updateJobExecutionTime(context, job.id, job.lastExecutedTime)
                            } else {
                                val dayName = SimpleDateFormat("EEEE", Locale.getDefault()).format(currentCal.time)
                                logActivity(
                                    context,
                                    "Automated",
                                    "${job.command} ${job.data}".uppercase(),
                                    job.file,
                                    "SKIPPED",
                                    "Today ($dayName) is not in allowed days ($daysLabel)"
                                )
                            }
                            removeJob(context, job.id)
                        }
                        activeJobCoroutines[job.id] = coroutineJob
                    }
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_SCHEDULER, "Error scheduling job ${job.id}: ${e.message}", e)
            }
        }
    }

    // ------------------------------------------------------------------------
    // Command Execution Engine
    // ------------------------------------------------------------------------

    suspend fun executeSingleCommand(
        context: Context,
        cmdObj: JSONObject,
        isCatchUp: Boolean = false,
        scheduledAt: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        val command = cmdObj.optString("command").trim().lowercase()
        val data = cmdObj.optString("data").trim().lowercase()
        val customFile = cmdObj.optString("file", "").trim()
        val defaultFile = when {
            data == "stats" -> "rethink_automated_stats.xls"
            command == "export" && data == "state" -> "rethink_automated_state.json"
            else -> "rethink_state.json"
        }
        val fileLabel = if (customFile.isNotEmpty()) customFile else defaultFile
        val runTime = System.currentTimeMillis()
        val nowFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(runTime))
        val executionNote = if (isCatchUp) {
            " [Catch-up: scheduled for $scheduledAt, executed at $nowFormatted]"
        } else {
            " [Executed at $nowFormatted]"
        }

        return@withContext when {
            command == "import" && data == "state" -> {
                val targetUri = if (customFile.isNotEmpty()) {
                    getAuthorizedFileUri(context, customFile)
                } else {
                    getAuthorizedFileUri(context, "rethink_state.json", "state.json")
                }
                var stateJsonString: String? = null
                if (targetUri != null) {
                    try {
                        context.contentResolver.openInputStream(targetUri)?.use { inputStream ->
                            stateJsonString = InputStreamReader(inputStream).readText()
                        }
                    } catch (e: Exception) {
                        Logger.e(LOG_TAG_UI, "Failed to read $targetUri: ${e.message}", e)
                    }
                }
                if (stateJsonString.isNullOrBlank() && customFile.isEmpty()) {
                    val fallbackUri = getAuthorizedFileUri(context, "rethink_automated_state.json")
                    if (fallbackUri != null) {
                        try {
                            context.contentResolver.openInputStream(fallbackUri)?.use { inputStream ->
                                stateJsonString = InputStreamReader(inputStream).readText()
                            }
                        } catch (e: Exception) {
                            Logger.e(LOG_TAG_UI, "Failed to read fallback: ${e.message}", e)
                        }
                    }
                }
                if (stateJsonString.isNullOrBlank()) {
                    val err = "No valid state file found for '$fileLabel'.$executionNote"
                    Logger.e(LOG_TAG_UI, err)
                    logActivity(context, "Automated", "Import State", fileLabel, "FAILED", err)
                    false
                } else {
                    try {
                        val cleanJson = if (stateJsonString!!.startsWith("\uFEFF")) stateJsonString!!.substring(1).trim() else stateJsonString!!.trim()
                        val json = JSONObject(cleanJson)
                        importStateFromJson(context, json)
                        val successMsg = "Firewall & DNS rules reloaded$executionNote"
                        Logger.i(LOG_TAG_UI, "Command executed: State imported successfully from $fileLabel$executionNote")
                        logActivity(context, "Automated", "Import State", fileLabel, "SUCCESS", successMsg)
                        true
                    } catch (e: Exception) {
                        val err = "Error applying state: ${e.message}$executionNote"
                        Logger.e(LOG_TAG_UI, err, e)
                        logActivity(context, "Automated", "Import State", fileLabel, "FAILED", err)
                        false
                    }
                }
            }

            command == "export" && data == "state" -> {
                val savedUri = if (customFile.isNotEmpty()) {
                    getAuthorizedFileUri(context, customFile)
                } else {
                    getAuthorizedFileUri(context, "rethink_automated_state.json", "rethink_state.json", "state.json")
                }
                if (savedUri != null) {
                    try {
                        exportStateToJson(context, savedUri)
                        val successMsg = "State saved to $fileLabel$executionNote"
                        Logger.i(LOG_TAG_UI, "Command executed: State exported successfully to $fileLabel$executionNote")
                        logActivity(context, "Automated", "Export State", fileLabel, "SUCCESS", successMsg)
                        true
                    } catch (e: Exception) {
                        val err = "Error exporting state: ${e.message}$executionNote"
                        Logger.e(LOG_TAG_UI, err, e)
                        logActivity(context, "Automated", "Export State", fileLabel, "FAILED", err)
                        false
                    }
                } else {
                    val err = "No export file found for '$fileLabel'.$executionNote"
                    Logger.e(LOG_TAG_UI, err)
                    logActivity(context, "Automated", "Export State", fileLabel, "FAILED", err)
                    false
                }
            }

            command == "export" && data == "stats" -> {
                val savedUri = if (customFile.isNotEmpty()) {
                    getAuthorizedFileUri(context, customFile)
                } else {
                    getAuthorizedFileUri(context, "rethink_automated_stats.xls", "rethink_stats.xls", "stats.xls", "rethink_logs.xls")
                }
                if (savedUri != null) {
                    try {
                        val resolvedRange = resolveExportTimeRange(cmdObj, runTime)
                        val fromTime = resolvedRange?.first
                        val toTime = resolvedRange?.second

                        exportLogsToExcel(context, savedUri, fromTime, toTime)
                        val sdfLog = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                        val rangeStr = if (fromTime != null && toTime != null) {
                            " (${sdfLog.format(Date(fromTime))} -> ${sdfLog.format(Date(toTime))})"
                        } else {
                            ""
                        }
                        val successMsg = "Network logs exported to $fileLabel$rangeStr$executionNote"
                        Logger.i(LOG_TAG_UI, "Command executed: Stats exported to $fileLabel$rangeStr$executionNote")
                        logActivity(context, "Automated", "Export Stats", fileLabel, "SUCCESS", successMsg)
                        true
                    } catch (e: Exception) {
                        val err = "Error exporting stats: ${e.message}$executionNote"
                        Logger.e(LOG_TAG_UI, err, e)
                        logActivity(context, "Automated", "Export Stats", fileLabel, "FAILED", err)
                        false
                    }
                } else {
                    val err = "No export stats file found for '$fileLabel'.$executionNote"
                    Logger.e(LOG_TAG_UI, err)
                    logActivity(context, "Automated", "Export Stats", fileLabel, "FAILED", err)
                    false
                }
            }

            else -> {
                Logger.w(LOG_TAG_UI, "Unknown command: $command $data")
                true
            }
        }
    }

    // ------------------------------------------------------------------------
    // State Export / Import & Log Export
    // ------------------------------------------------------------------------

    suspend fun exportStateToJson(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val state = VpnController.state()
        val status = if (state.on) "started" else "stopped"
        val json = JSONObject()
        json.put("status", status)

        val universal = JSONObject().apply {
            put("blockWhenDeviceLocked", persistentState.getBlockWhenDeviceLocked())
            put("blockAppWhenBackground", persistentState.getBlockAppWhenBackground())
            put("blockUnknownConnections", persistentState.getBlockUnknownConnections())
            put("udpBlocked", persistentState.getUdpBlocked())
            put("disallowDnsBypass", persistentState.getDisallowDnsBypass())
            put("blockNewlyInstalledApp", persistentState.getBlockNewlyInstalledApp())
            put("blockMeteredConnections", persistentState.getBlockMeteredConnections())
            put("blockHttpConnections", persistentState.getBlockHttpConnections())
            put("universalLockdown", persistentState.getUniversalLockdown())
        }
        json.put("universal", universal)

        val ipportParent = JSONObject()
        val perappParent = JSONObject()

        val ipRules = customIpRepository.getIpRules()
        val universalIpportArray = JSONArray()
        val perappIpportArray = JSONArray()
        for (rule in ipRules) {
            val ruleJson = JSONObject().apply {
                put("uid", rule.uid)
                put("ipAddress", rule.ipAddress)
                put("port", rule.port)
                put("protocol", rule.protocol)
                put("isActive", rule.isActive)
                put("status", rule.status)
            }
            if (rule.uid == Constants.UID_EVERYBODY) {
                universalIpportArray.put(ruleJson)
            } else {
                perappIpportArray.put(ruleJson)
            }
        }
        ipportParent.put("ipport", universalIpportArray)
        perappParent.put("ipport", perappIpportArray)

        val domainRules = customDomainRepository.getAllCustomDomains()
        val universalDomainArray = JSONArray()
        val perappDomainArray = JSONArray()
        for (rule in domainRules) {
            val ruleJson = JSONObject().apply {
                put("domain", rule.domain)
                put("uid", rule.uid)
                put("ips", rule.ips)
                put("status", rule.status)
                put("type", rule.type)
            }
            if (rule.uid == Constants.UID_EVERYBODY) {
                universalDomainArray.put(ruleJson)
            } else {
                perappDomainArray.put(ruleJson)
            }
        }
        ipportParent.put("domain", universalDomainArray)
        perappParent.put("domain", perappDomainArray)

        json.put("ipport", ipportParent)
        json.put("perapp", perappParent)

        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            OutputStreamWriter(outputStream).use { writer ->
                writer.write(json.toString(2))
            }
        }
    }

    suspend fun importStateFromJson(context: Context, json: JSONObject) = withContext(Dispatchers.IO) {
        if (json.has("status")) {
            val status = json.getString("status")
            val vpnState = VpnController.state()
            withContext(Dispatchers.Main) {
                if (status == "started" && !vpnState.on) {
                    VpnController.start(context)
                } else if (status == "stopped" && vpnState.on) {
                    VpnController.stop("State imported from JSON", context)
                }
            }
        }

        if (json.has("universal")) {
            val universal = json.getJSONObject("universal")
            if (universal.has("blockWhenDeviceLocked")) persistentState.setBlockWhenDeviceLocked(universal.getBoolean("blockWhenDeviceLocked"))
            if (universal.has("blockAppWhenBackground")) persistentState.setBlockAppWhenBackground(universal.getBoolean("blockAppWhenBackground"))
            if (universal.has("blockUnknownConnections")) persistentState.setBlockUnknownConnections(universal.getBoolean("blockUnknownConnections"))
            if (universal.has("udpBlocked")) persistentState.setUdpBlocked(universal.getBoolean("udpBlocked"))
            if (universal.has("disallowDnsBypass")) persistentState.setDisallowDnsBypass(universal.getBoolean("disallowDnsBypass"))
            if (universal.has("blockNewlyInstalledApp")) persistentState.setBlockNewlyInstalledApp(universal.getBoolean("blockNewlyInstalledApp"))
            if (universal.has("blockMeteredConnections")) persistentState.setBlockMeteredConnections(universal.getBoolean("blockMeteredConnections"))
            if (universal.has("blockHttpConnections")) persistentState.setBlockHttpConnections(universal.getBoolean("blockHttpConnections"))
            if (universal.has("universalLockdown")) persistentState.setUniversalLockdown(universal.getBoolean("universalLockdown"))
        }

        if (json.has("ipport")) {
            val ipportParent = json.getJSONObject("ipport")
            if (ipportParent.has("ipport")) {
                importCustomIps(ipportParent.getJSONArray("ipport"), true)
            }
            if (ipportParent.has("domain")) {
                importCustomDomains(ipportParent.getJSONArray("domain"), true)
            }
        }

        if (json.has("perapp")) {
            val perappParent = json.getJSONObject("perapp")
            if (perappParent.has("ipport")) {
                importCustomIps(perappParent.getJSONArray("ipport"), false)
            }
            if (perappParent.has("domain")) {
                importCustomDomains(perappParent.getJSONArray("domain"), false)
            }
        }

        try {
            DomainRulesManager.load()
            IpRulesManager.load()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "Error reloading rules into engine: ${e.message}", e)
        }
    }

    private suspend fun importCustomIps(array: JSONArray, isUniversal: Boolean) {
        val currentRules = customIpRepository.getIpRules().filter {
            if (isUniversal) it.uid == Constants.UID_EVERYBODY else it.uid != Constants.UID_EVERYBODY
        }
        for (i in 0 until array.length()) {
            val ruleJson = array.getJSONObject(i)
            val ipAddress = ruleJson.getString("ipAddress")
            val port = ruleJson.getInt("port")
            val uid = ruleJson.getInt("uid")
            val protocol = ruleJson.getString("protocol")
            val isActive = ruleJson.getBoolean("isActive")
            val status = ruleJson.getInt("status")

            val existing = currentRules.find { it.ipAddress == ipAddress && it.port == port && it.protocol == protocol && it.uid == uid }
            if (existing == null) {
                val newRule = com.celzero.bravedns.database.CustomIp()
                newRule.ipAddress = ipAddress
                newRule.port = port
                newRule.protocol = protocol
                newRule.isActive = isActive
                newRule.status = status
                newRule.uid = uid
                customIpRepository.insert(newRule)
            } else if (existing.isActive != isActive || existing.status != status) {
                existing.isActive = isActive
                existing.status = status
                customIpRepository.update(existing)
            }
        }
    }

    private suspend fun importCustomDomains(array: JSONArray, isUniversal: Boolean) {
        val currentRules = customDomainRepository.getAllCustomDomains().filter {
            if (isUniversal) it.uid == Constants.UID_EVERYBODY else it.uid != Constants.UID_EVERYBODY
        }
        for (i in 0 until array.length()) {
            val ruleJson = array.getJSONObject(i)
            val domain = ruleJson.getString("domain")
            val uid = ruleJson.getInt("uid")
            val status = ruleJson.getInt("status")
            val type = ruleJson.getInt("type")
            val ips = ruleJson.optString("ips", "")

            val existing = currentRules.find { it.domain == domain && it.uid == uid }
            if (existing == null) {
                val newRule = com.celzero.bravedns.database.CustomDomain(null)
                newRule.domain = domain
                newRule.uid = uid
                newRule.status = status
                newRule.type = type
                newRule.ips = ips
                customDomainRepository.insert(newRule)
            } else if (existing.status != status || existing.type != type || existing.ips != ips) {
                val newRule = com.celzero.bravedns.database.CustomDomain(null)
                newRule.domain = domain
                newRule.uid = uid
                newRule.status = status
                newRule.type = type
                newRule.ips = ips
                customDomainRepository.update(existing, newRule)
            }
        }
    }

    suspend fun exportLogsToExcel(
        context: Context,
        uri: Uri,
        fromTime: Long? = null,
        toTime: Long? = null
    ) = withContext(Dispatchers.IO) {
        val actualFrom = fromTime ?: run {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }
        val actualTo = toTime ?: System.currentTimeMillis()

        val allLogs = connectionTrackerRepository.getAllLogs()
        val logs = allLogs.filter {
            if (toTime != null) {
                it.timeStamp >= actualFrom && it.timeStamp < actualTo
            } else {
                it.timeStamp in actualFrom..actualTo
            }
        }
        val workbook = HSSFWorkbook()
        val sheet = workbook.createSheet("Network Logs")

        val headers = arrayOf(
            "Time", "App Name", "Package", "IP Address", "Port",
            "Protocol", "Blocked?", "Blocked By", "Target IP",
            "Flag", "Message", "Download (B)", "Upload (B)"
        )

        val headerRow = sheet.createRow(0)
        for ((index, header) in headers.withIndex()) {
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
        }

        val columnWidths = intArrayOf(5500, 6000, 7000, 4500, 2000, 2500, 2500, 4000, 6000, 3000, 8000, 4000, 4000)
        for (i in columnWidths.indices) {
            sheet.setColumnWidth(i, columnWidths[i])
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        for ((rowIndex, log) in logs.withIndex()) {
            val row = sheet.createRow(rowIndex + 1)
            row.createCell(0).setCellValue(dateFormat.format(Date(log.timeStamp)))
            row.createCell(1).setCellValue(log.appName ?: "")
            row.createCell(2).setCellValue(log.packageName ?: "")
            row.createCell(3).setCellValue(log.ipAddress ?: "")
            row.createCell(4).setCellValue(log.port.toString())
            row.createCell(5).setCellValue(if (log.protocol == 1) "UDP" else if (log.protocol == 2) "TCP" else "OTHER")
            row.createCell(6).setCellValue(if (log.isBlocked) "Yes" else "No")
            row.createCell(7).setCellValue(log.blockedByRule ?: "")
            row.createCell(8).setCellValue(log.dnsQuery ?: "")
            row.createCell(9).setCellValue(log.flag ?: "")
            row.createCell(10).setCellValue(log.message ?: "")
            row.createCell(11).setCellValue(log.downloadBytes.toString())
            row.createCell(12).setCellValue(log.uploadBytes.toString())
        }

        sheet.createFreezePane(0, 1)
        if (logs.isNotEmpty()) {
            sheet.setAutoFilter(org.apache.poi.ss.util.CellRangeAddress(0, logs.size, 0, headers.size - 1))
        }

        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            workbook.write(outputStream)
        }
        workbook.close()
    }

    // ------------------------------------------------------------------------
    // Parsing & Resolution Helpers
    // ------------------------------------------------------------------------

    fun getAuthorizedFileUri(context: Context, vararg candidateNames: String): Uri? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_AUTHORIZED_FILES, "{}") ?: "{}"
        try {
            val jsonObject = JSONObject(jsonString)
            for (name in candidateNames) {
                val trimmed = name.trim()
                if (jsonObject.has(trimmed)) {
                    val uriStr = jsonObject.getString(trimmed)
                    if (!uriStr.isNullOrEmpty()) return Uri.parse(uriStr)
                }
            }
            val keys = jsonObject.keys()
            val keyList = mutableListOf<String>()
            while (keys.hasNext()) {
                keyList.add(keys.next())
            }
            for (candidate in candidateNames) {
                val trimmed = candidate.trim()
                val matchedKey = keyList.find { it.trim().equals(trimmed, ignoreCase = true) }
                if (matchedKey != null) {
                    val uriStr = jsonObject.getString(matchedKey)
                    if (!uriStr.isNullOrEmpty()) return Uri.parse(uriStr)
                }
            }
            for (candidate in candidateNames) {
                val trimmed = candidate.trim()
                val matchedKey = keyList.find { it.contains(trimmed, ignoreCase = true) }
                if (matchedKey != null) {
                    val uriStr = jsonObject.getString(matchedKey)
                    if (!uriStr.isNullOrEmpty()) return Uri.parse(uriStr)
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "Error reading authorized files map: ${e.message}", e)
        }
        return null
    }

    @Synchronized
    fun logActivity(
        context: Context,
        type: String,
        action: String,
        fileName: String,
        status: String,
        details: String = ""
    ) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val logsJson = prefs.getString(KEY_ACTIVITY_LOGS, "[]") ?: "[]"
            val array = JSONArray(logsJson)
            val obj = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("type", type)
                put("action", action)
                put("fileName", fileName)
                put("status", status)
                put("details", details)
            }
            val newArray = JSONArray()
            newArray.put(obj)
            val limit = minOf(array.length(), 99)
            for (i in 0 until limit) {
                newArray.put(array.getJSONObject(i))
            }
            prefs.edit().putString(KEY_ACTIVITY_LOGS, newArray.toString()).apply()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "Error writing activity log: ${e.message}", e)
        }
    }

    fun parsePeriodMillis(periodStr: String): Long? {
        val trimmed = periodStr.trim()
        val regex = Regex("""(\d+)\s*([dhmsDHMS])""")
        val matches = regex.findAll(trimmed).toList()
        if (matches.isEmpty()) return null
        var totalMillis = 0L
        for (match in matches) {
            val amount = match.groupValues[1].toLongOrNull() ?: return null
            val unit = match.groupValues[2].lowercase(Locale.US)
            val millis = when (unit) {
                "d" -> amount * 24 * 60 * 60 * 1000L
                "h" -> amount * 60 * 60 * 1000L
                "m" -> amount * 60 * 1000L
                "s" -> amount * 1000L
                else -> 0L
            }
            totalMillis += millis
        }
        return if (totalMillis > 0) totalMillis else null
    }

    fun parseTimeOfDay(raw: String): Pair<Int, Int>? {
        val trimmed = raw.trim()
        val amPmRegex = Regex("""^(\d{1,2})(?::(\d{2}))?\s*(AM|PM|am|pm)$""", RegexOption.IGNORE_CASE)
        val matchAmPm = amPmRegex.find(trimmed)
        if (matchAmPm != null) {
            val hStr = matchAmPm.groupValues[1]
            val mStr = matchAmPm.groupValues[2]
            val amPm = matchAmPm.groupValues[3].uppercase(Locale.US)
            var hour = hStr.toIntOrNull() ?: return null
            val minute = if (mStr.isNotEmpty()) mStr.toIntOrNull() ?: 0 else 0
            if (hour !in 1..12 || minute !in 0..59) return null
            if (amPm == "PM" && hour < 12) hour += 12
            if (amPm == "AM" && hour == 12) hour = 0
            return Pair(hour, minute)
        }

        val h24Regex = Regex("""^(\d{1,2}):(\d{2})(?::(\d{2}))?$""")
        val match24 = h24Regex.find(trimmed)
        if (match24 != null) {
            val hour = match24.groupValues[1].toIntOrNull() ?: return null
            val minute = match24.groupValues[2].toIntOrNull() ?: return null
            if (hour in 0..23 && minute in 0..59) {
                return Pair(hour, minute)
            }
        }
        return null
    }

    fun parseFullDateTime(raw: String): Long? {
        val patterns = arrayOf(
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd hh:mm a",
            "yyyy-MM-dd hh:mm a",
            "yyyy/MM/dd hh:mma",
            "yyyy-MM-dd hh:mma"
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.isLenient = false
                val date = sdf.parse(raw.trim())
                if (date != null) return date.time
            } catch (_: Exception) {}
        }
        return null
    }

    fun parseDays(cmdObj: JSONObject): Pair<Set<Int>?, String> {
        if (!cmdObj.has("days")) {
            return Pair(null, "ALL")
        }
        val daysVal = cmdObj.get("days")
        val rawDays = mutableListOf<String>()

        if (daysVal is JSONArray) {
            for (i in 0 until daysVal.length()) {
                val item = daysVal.optString(i).trim()
                if (item.isNotEmpty()) rawDays.add(item)
            }
        } else {
            val str = daysVal.toString().trim()
            if (str.isEmpty() || str.equals("ALL", ignoreCase = true)) {
                return Pair(null, "ALL")
            }
            str.split(",").forEach {
                val trimmed = it.trim()
                if (trimmed.isNotEmpty()) rawDays.add(trimmed)
            }
        }

        if (rawDays.isEmpty() || rawDays.any { it.equals("ALL", ignoreCase = true) }) {
            return Pair(null, "ALL")
        }

        val calendarDays = mutableSetOf<Int>()
        for (dayStr in rawDays) {
            when (dayStr.uppercase()) {
                "SUN", "SUNDAY" -> calendarDays.add(Calendar.SUNDAY)
                "MON", "MONDAY" -> calendarDays.add(Calendar.MONDAY)
                "TUE", "TUES", "TUESDAY" -> calendarDays.add(Calendar.TUESDAY)
                "WED", "WEDNESDAY" -> calendarDays.add(Calendar.WEDNESDAY)
                "THU", "THUR", "THURS", "THURSDAY" -> calendarDays.add(Calendar.THURSDAY)
                "FRI", "FRIDAY" -> calendarDays.add(Calendar.FRIDAY)
                "SAT", "SATURDAY" -> calendarDays.add(Calendar.SATURDAY)
                else -> Logger.w(LOG_TAG_UI, "Unknown day in 'days': $dayStr")
            }
        }

        return if (calendarDays.isEmpty() || calendarDays.size == 7) {
            Pair(null, "ALL")
        } else {
            Pair(calendarDays, rawDays.joinToString(","))
        }
    }

    fun resolveExportTimeRange(cmdObj: JSONObject, runTime: Long): Pair<Long, Long>? {
        if (cmdObj.has("period")) {
            val periodStr = cmdObj.getString("period")
            val millis = parsePeriodMillis(periodStr)
            if (millis != null) {
                val toTime = runTime
                val fromTime = toTime - millis
                return Pair(fromTime, toTime)
            } else {
                Logger.w(LOG_TAG_UI, "Invalid period format: $periodStr")
            }
        }

        val fromRaw = cmdObj.optString("from", "").trim()
        val toRaw = cmdObj.optString("to", "").trim()

        if (fromRaw.isEmpty() && toRaw.isEmpty()) {
            val startOfDay = Calendar.getInstance().apply {
                timeInMillis = runTime
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            return Pair(startOfDay, runTime)
        }

        val fullFrom = if (fromRaw.isNotEmpty()) parseFullDateTime(fromRaw) else null
        val fullTo = if (toRaw.isNotEmpty()) parseFullDateTime(toRaw) else null

        if (fullFrom != null || fullTo != null) {
            val from = fullFrom ?: Calendar.getInstance().apply {
                timeInMillis = fullTo ?: runTime
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val to = fullTo ?: runTime
            return Pair(from, to)
        }

        val fromTod = if (fromRaw.isNotEmpty()) parseTimeOfDay(fromRaw) else null
        val toTod = if (toRaw.isNotEmpty()) parseTimeOfDay(toRaw) else null

        if (fromTod != null && toTod != null) {
            val (h1, m1) = fromTod
            val (h2, m2) = toTod

            // Full-day case (e.g., 00:00 to 23:59)
            if (h1 == 0 && m1 == 0 && h2 == 23 && m2 >= 50) {
                val startCal = Calendar.getInstance().apply {
                    timeInMillis = runTime
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val endCal = Calendar.getInstance().apply {
                    timeInMillis = startCal.timeInMillis
                    add(Calendar.DAY_OF_MONTH, 1)
                }
                return Pair(startCal.timeInMillis, endCal.timeInMillis)
            }

            val minutes1 = h1 * 60 + m1
            val minutes2 = h2 * 60 + m2

            val (earlierH, earlierM) = if (minutes1 <= minutes2) Pair(h1, m1) else Pair(h2, m2)
            val (laterH, laterM) = if (minutes1 <= minutes2) Pair(h2, m2) else Pair(h1, m1)

            val endCal = Calendar.getInstance().apply {
                timeInMillis = runTime
                set(Calendar.HOUR_OF_DAY, earlierH)
                set(Calendar.MINUTE, earlierM)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (endCal.timeInMillis > runTime) {
                endCal.add(Calendar.DAY_OF_MONTH, -1)
            }

            val startCal = Calendar.getInstance().apply {
                timeInMillis = endCal.timeInMillis
                add(Calendar.DAY_OF_MONTH, -1)
                set(Calendar.HOUR_OF_DAY, laterH)
                set(Calendar.MINUTE, laterM)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return Pair(startCal.timeInMillis, endCal.timeInMillis)
        }

        if (fromTod != null) {
            val (h, m) = fromTod
            val cal = Calendar.getInstance().apply {
                timeInMillis = runTime
                set(Calendar.HOUR_OF_DAY, h)
                set(Calendar.MINUTE, m)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis > runTime) {
                cal.add(Calendar.DAY_OF_MONTH, -1)
            }
            return Pair(cal.timeInMillis, runTime)
        }

        if (toTod != null) {
            val (h, m) = toTod
            val toCal = Calendar.getInstance().apply {
                timeInMillis = runTime
                set(Calendar.HOUR_OF_DAY, h)
                set(Calendar.MINUTE, m)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (toCal.timeInMillis > runTime) {
                toCal.add(Calendar.DAY_OF_MONTH, -1)
            }
            val fromCal = Calendar.getInstance().apply {
                timeInMillis = toCal.timeInMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return Pair(fromCal.timeInMillis, toCal.timeInMillis)
        }

        val startOfDay = Calendar.getInstance().apply {
            timeInMillis = runTime
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return Pair(startOfDay, runTime)
    }
}
