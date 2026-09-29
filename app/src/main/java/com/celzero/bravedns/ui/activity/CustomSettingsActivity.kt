package com.celzero.bravedns.ui.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.databinding.ActivityCustomSettingsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.CustomIpRepository
import com.celzero.bravedns.database.CustomDomainRepository
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.util.Themes
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import org.koin.android.ext.android.inject
import java.io.OutputStreamWriter
import java.io.InputStreamReader
import android.content.res.Configuration

import org.apache.poi.ss.usermodel.*
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

class CustomSettingsActivity : BaseActivity() {

    private lateinit var b: ActivityCustomSettingsBinding
    private val persistentState by inject<PersistentState>()
    private val appInfoRepository by inject<AppInfoRepository>()
    private val customIpRepository by inject<CustomIpRepository>()
    private val customDomainRepository by inject<CustomDomainRepository>()
    private val connectionTrackerRepository by inject<ConnectionTrackerRepository>()
    private val pendingCommandJobs = mutableListOf<kotlinx.coroutines.Job>()

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        try {
            super.onCreate(savedInstanceState)
            b = ActivityCustomSettingsBinding.inflate(layoutInflater)
            setContentView(b.root)

            b.acsAppLockCard.setOnClickListener {
                showAppLockOptionsDialog()
            }

            val prefs = getSharedPreferences("RethinkPrefs", Context.MODE_PRIVATE)

            b.acsExportStateCard.setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    prefs.getString("last_export_state_uri", null)?.let { uriStr ->
                        putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.net.Uri.parse(uriStr))
                    }
                }
                openDocumentTreeLauncher.launch(intent)
            }

            b.acsImportStateCard.setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    prefs.getString("last_import_state_uri", null)?.let { uriStr ->
                        putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.net.Uri.parse(uriStr))
                    }
                }
                openStateTreeLauncher.launch(intent)
            }

            b.acsImportCommandsCard.setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    val mimeTypes = arrayOf("application/json", "text/plain", "text/javascript", "application/octet-stream")
                    putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                    prefs.getString("last_import_commands_uri", null)?.let { uriStr ->
                        putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.net.Uri.parse(uriStr))
                    }
                }
                importCommandsLauncher.launch(intent)
            }

            b.acsExportExcelCard.setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    prefs.getString("last_export_stats_uri", null)?.let { uriStr ->
                        putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.net.Uri.parse(uriStr))
                    }
                }
                openExcelTreeLauncher.launch(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e("CustomSettings", "Crash in onCreate", e)
            Toast.makeText(this, "Crash: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val prefs = getSharedPreferences("RethinkPrefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("last_export_state_uri", uri.toString()).apply()
                    } catch (e: Exception) {
                        android.util.Log.e("CustomSettings", "Failed to take persistable URI permission for tree", e)
                    }
                    
                    lifecycleScope.launch {
                        try {
                            val formatter = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                            val timestamp = formatter.format(java.util.Date())
                            val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                            val dirUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                            val newFileUri = android.provider.DocumentsContract.createDocument(contentResolver, dirUri, "application/json", "rethink_state_${timestamp}.json")
                            if (newFileUri != null) {
                                exportStateToJson(newFileUri)
                                Toast.makeText(this@CustomSettingsActivity, "State exported to folder successfully", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@CustomSettingsActivity, "Could not create file in folder", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this@CustomSettingsActivity, "Error exporting state", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

    private suspend fun exportStateToJson(uri: android.net.Uri) {
        val state = VpnController.state()
        val status = if (state.on) "started" else "stopped"
        val json = JSONObject()
        json.put("status", status)

        val universal = JSONObject()
        universal.put("blockWhenDeviceLocked", persistentState.getBlockWhenDeviceLocked())
        universal.put("blockAppWhenBackground", persistentState.getBlockAppWhenBackground())
        universal.put("udpBlocked", persistentState.getUdpBlocked())
        universal.put("blockUnknownConnections", persistentState.getBlockUnknownConnections())
        universal.put("disallowDnsBypass", persistentState.getDisallowDnsBypass())
        universal.put("blockNewlyInstalledApp", persistentState.getBlockNewlyInstalledApp())
        universal.put("blockMeteredConnections", persistentState.getBlockMeteredConnections())

        json.put("universal", universal)

        val ipportParent = JSONObject()
        val perappParent = JSONObject()

        val ipRules = withContext(Dispatchers.IO) { customIpRepository.getIpRules() }
        val universalIpportArray = JSONArray()
        val perappIpportArray = JSONArray()
        for (rule in ipRules) {
            val ruleJson = JSONObject()
            ruleJson.put("uid", rule.uid)
            ruleJson.put("ipAddress", rule.ipAddress)
            ruleJson.put("port", rule.port)
            ruleJson.put("protocol", rule.protocol)
            ruleJson.put("isActive", rule.isActive)
            ruleJson.put("status", rule.status)
            
            if (rule.uid == com.celzero.bravedns.util.Constants.UID_EVERYBODY) {
                universalIpportArray.put(ruleJson)
            } else {
                perappIpportArray.put(ruleJson)
            }
        }
        ipportParent.put("ipport", universalIpportArray)
        perappParent.put("ipport", perappIpportArray)

        val domainRules = withContext(Dispatchers.IO) { customDomainRepository.getAllCustomDomains() }
        val universalDomainArray = JSONArray()
        val perappDomainArray = JSONArray()
        for (rule in domainRules) {
            val ruleJson = JSONObject()
            ruleJson.put("domain", rule.domain)
            ruleJson.put("uid", rule.uid)
            ruleJson.put("ips", rule.ips)
            ruleJson.put("status", rule.status)
            ruleJson.put("type", rule.type)
            
            if (rule.uid == com.celzero.bravedns.util.Constants.UID_EVERYBODY) {
                universalDomainArray.put(ruleJson)
            } else {
                perappDomainArray.put(ruleJson)
            }
        }
        ipportParent.put("domain", universalDomainArray)
        perappParent.put("domain", perappDomainArray)

        json.put("ipport", ipportParent)
        json.put("perapp", perappParent)

        withContext(Dispatchers.IO) {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(json.toString(2))
                }
            }
        }
    }

    private val openExcelTreeLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val prefs = getSharedPreferences("RethinkPrefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("last_export_stats_uri", uri.toString()).apply()
                    } catch (e: Exception) {
                        android.util.Log.e("CustomSettings", "Failed to take persistable URI permission for stats", e)
                    }

                    lifecycleScope.launch {
                        try {
                            val formatter = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                            val timestamp = formatter.format(java.util.Date())
                            val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                            val dirUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                            val newFileUri = android.provider.DocumentsContract.createDocument(contentResolver, dirUri, "application/vnd.ms-excel", "rethink_stats_${timestamp}.xls")
                            if (newFileUri != null) {
                                Toast.makeText(this@CustomSettingsActivity, "Exporting stats to Excel...", Toast.LENGTH_SHORT).show()
                                exportLogsToExcel(newFileUri)
                                Toast.makeText(this@CustomSettingsActivity, "Stats exported to folder successfully", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@CustomSettingsActivity, "Could not create file in folder", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Throwable) {
                            android.util.Log.e("CustomSettings", "Error exporting Excel", e)
                            Toast.makeText(this@CustomSettingsActivity, "Error exporting Excel: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

    private suspend fun exportLogsToExcel(uri: android.net.Uri, fromTime: Long? = null, toTime: Long? = null) = withContext(Dispatchers.IO) {
        val actualFrom = fromTime ?: run {
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }
        val actualTo = toTime ?: System.currentTimeMillis()
        
        val allLogs = connectionTrackerRepository.getAllLogs()
        val logs = allLogs.filter { it.timeStamp in actualFrom..actualTo }
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

        // Set reasonable fixed widths instead of autoSizeColumn (which crashes on Android without awt fonts)
        // Values are in units of 1/256th of a character width
        val columnWidths = intArrayOf(
            5500, // Time
            6000, // App Name
            7000, // Package
            4500, // IP Address
            2000, // Port
            2500, // Protocol
            2500, // Blocked?
            4000, // Blocked By
            6000, // Target IP
            3000, // Flag
            8000, // Message
            4000, // Download
            4000  // Upload
        )
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
            row.createCell(8).setCellValue(log.dnsQuery ?: "") // dnsQuery stores the Target IP or Domain
            row.createCell(9).setCellValue(log.flag ?: "")
            row.createCell(10).setCellValue(log.message ?: "")
            row.createCell(11).setCellValue(log.downloadBytes.toString())
            row.createCell(12).setCellValue(log.uploadBytes.toString())
        }

        sheet.createFreezePane(0, 1) // Freeze top row
        // Add auto filter
        sheet.setAutoFilter(org.apache.poi.ss.util.CellRangeAddress(0, logs.size, 0, headers.size - 1))

        contentResolver.openOutputStream(uri)?.use { outputStream ->
            workbook.write(outputStream)
        }
        workbook.close()
    }

    private val openStateTreeLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        val prefs = getSharedPreferences("RethinkPrefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("last_import_state_uri", uri.toString()).apply()
                    } catch (e: Exception) {
                        android.util.Log.e("CustomSettings", "Failed to take persistable URI permission", e)
                    }

                    lifecycleScope.launch {
                        try {
                            val fileUri = findFileUriInTree(uri, "rethink_state.json")
                            if (fileUri != null) {
                                val jsonString = withContext(Dispatchers.IO) {
                                    contentResolver.openInputStream(fileUri)?.use { inputStream ->
                                        InputStreamReader(inputStream).readText()
                                    }
                                }
                                if (jsonString != null) {
                                    val json = JSONObject(jsonString)
                                    importStateFromJson(json)
                                    Toast.makeText(this@CustomSettingsActivity, "State imported successfully from folder", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(this@CustomSettingsActivity, "rethink_state.json not found in the selected folder", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("CustomSettings", "Error importing state", e)
                            Toast.makeText(this@CustomSettingsActivity, "Error importing state: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

    private val importCommandsLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val prefs = getSharedPreferences("RethinkPrefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("last_import_commands_uri", uri.toString()).apply()
                    } catch (e: Exception) {
                        android.util.Log.e("CustomSettings", "Failed to take persistable URI permission", e)
                    }

                    lifecycleScope.launch {
                        try {
                            val jsonString = withContext(Dispatchers.IO) {
                                contentResolver.openInputStream(uri)?.use { inputStream ->
                                    InputStreamReader(inputStream).readText()
                                }
                            }
                            if (jsonString != null) {
                                val prefs = getSharedPreferences("RethinkPrefs", Context.MODE_PRIVATE)
                                val root = org.json.JSONTokener(jsonString).nextValue()
                                val commandsArray = if (root is org.json.JSONArray) {
                                    root
                                } else if (root is JSONObject) {
                                    root.optJSONArray("commands") ?: org.json.JSONArray()
                                } else {
                                    org.json.JSONArray()
                                }

                                pendingCommandJobs.forEach { it.cancel() }
                                pendingCommandJobs.clear()

                                for (i in 0 until commandsArray.length()) {
                                    val cmdObj = commandsArray.optJSONObject(i) ?: continue
                                    if (!cmdObj.optBoolean("execute", false)) {
                                        continue
                                    }
                                    
                                    val command = cmdObj.optString("command")
                                    val data = cmdObj.optString("data")
                                    
                                    val executeAtStr = cmdObj.optString("executeAt", null)
                                    var delayMillis = 0L
                                    var isRecurring = false
                                    if (!executeAtStr.isNullOrEmpty()) {
                                        try {
                                            if (executeAtStr.length <= 5 && executeAtStr.contains(":")) {
                                                val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                                val parsedTime = sdf.parse(executeAtStr)
                                                if (parsedTime != null) {
                                                    val now = java.util.Calendar.getInstance()
                                                    val target = java.util.Calendar.getInstance()
                                                    target.time = parsedTime
                                                    
                                                    val targetCal = java.util.Calendar.getInstance()
                                                    targetCal.set(java.util.Calendar.HOUR_OF_DAY, target.get(java.util.Calendar.HOUR_OF_DAY))
                                                    targetCal.set(java.util.Calendar.MINUTE, target.get(java.util.Calendar.MINUTE))
                                                    targetCal.set(java.util.Calendar.SECOND, 0)
                                                    targetCal.set(java.util.Calendar.MILLISECOND, 0)
                                                    
                                                    if (targetCal.before(now)) {
                                                        targetCal.add(java.util.Calendar.DAY_OF_MONTH, 1)
                                                    }
                                                    delayMillis = targetCal.timeInMillis - now.timeInMillis
                                                    isRecurring = true
                                                }
                                            } else {
                                                val sdf = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault())
                                                val executeTime = sdf.parse(executeAtStr)?.time ?: 0L
                                                val now = System.currentTimeMillis()
                                                if (executeTime > now) {
                                                    delayMillis = executeTime - now
                                                }
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("CustomSettings", "Invalid executeAt format: $executeAtStr")
                                        }
                                    }
                                    
                                    val task: suspend () -> Boolean = {
                                        var success = false
                                        
                                        if (command == "import" && data == "state") {
                                        val savedUriStr = prefs.getString("last_import_state_uri", null)
                                        if (savedUriStr != null) {
                                            val savedUri = android.net.Uri.parse(savedUriStr)
                                            try {
                                                val importFileName = cmdObj.optString("file", "rethink_state.json")
                                                val fileUri = findFileUriInTree(savedUri, importFileName)
                                                if (fileUri != null) {
                                                    val stateJsonString = withContext(Dispatchers.IO) {
                                                        contentResolver.openInputStream(fileUri)?.use { inputStream ->
                                                            InputStreamReader(inputStream).readText()
                                                        }
                                                    }
                                                    if (stateJsonString != null) {
                                                        importStateFromJson(JSONObject(stateJsonString))
                                                        Toast.makeText(this@CustomSettingsActivity, "Command executed: State imported", Toast.LENGTH_SHORT).show()
                                                        success = true
                                                    }
                                                } else {
                                                    Toast.makeText(this@CustomSettingsActivity, "Command failed: $importFileName not found", Toast.LENGTH_LONG).show()
                                                }
                                            } catch (e: Exception) {
                                                Toast.makeText(this@CustomSettingsActivity, "Error importing state: ${e.message}", Toast.LENGTH_LONG).show()
                                            }
                                        } else {
                                            Toast.makeText(this@CustomSettingsActivity, "No previously selected state import folder found", Toast.LENGTH_LONG).show()
                                        }
                                    } else if (command == "export" && data == "state") {
                                        val savedUriStr = prefs.getString("last_export_state_uri", null)
                                        if (savedUriStr != null) {
                                            val savedUri = android.net.Uri.parse(savedUriStr)
                                            try {
                                                val formatter = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                                                val timestamp = formatter.format(java.util.Date())
                                                val docId = android.provider.DocumentsContract.getTreeDocumentId(savedUri)
                                                val dirUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(savedUri, docId)
                                                val newFileUri = android.provider.DocumentsContract.createDocument(contentResolver, dirUri, "application/json", "rethink_state_${timestamp}.json")
                                                if (newFileUri != null) {
                                                    exportStateToJson(newFileUri)
                                                    Toast.makeText(this@CustomSettingsActivity, "Command executed: State exported", Toast.LENGTH_SHORT).show()
                                                    success = true
                                                } else {
                                                    Toast.makeText(this@CustomSettingsActivity, "Command failed: Could not create file", Toast.LENGTH_LONG).show()
                                                }
                                            } catch (e: Exception) {
                                                Toast.makeText(this@CustomSettingsActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                            }
                                        } else {
                                            Toast.makeText(this@CustomSettingsActivity, "No previously selected state export folder found", Toast.LENGTH_LONG).show()
                                        }
                                    } else if (command == "export" && data == "stats") {
                                        val savedUriStr = prefs.getString("last_export_stats_uri", null)
                                        if (savedUriStr != null) {
                                            val savedUri = android.net.Uri.parse(savedUriStr)
                                            try {
                                                val formatter = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                                                val timestamp = formatter.format(java.util.Date())
                                                val docId = android.provider.DocumentsContract.getTreeDocumentId(savedUri)
                                                val dirUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(savedUri, docId)
                                                val newFileUri = android.provider.DocumentsContract.createDocument(contentResolver, dirUri, "application/vnd.ms-excel", "rethink_stats_${timestamp}.xls")
                                                if (newFileUri != null) {
                                                    val sdf = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault())
                                                    var fromTime: Long? = null
                                                    if (cmdObj.has("from")) {
                                                        fromTime = sdf.parse(cmdObj.getString("from"))?.time
                                                    }
                                                    var toTime: Long? = null
                                                    if (cmdObj.has("to")) {
                                                        toTime = sdf.parse(cmdObj.getString("to"))?.time
                                                    }
                                                    
                                                    exportLogsToExcel(newFileUri, fromTime, toTime)
                                                    Toast.makeText(this@CustomSettingsActivity, "Command executed: Stats exported", Toast.LENGTH_SHORT).show()
                                                    success = true
                                                } else {
                                                    Toast.makeText(this@CustomSettingsActivity, "Command failed: Could not create stats file", Toast.LENGTH_LONG).show()
                                                }
                                            } catch (e: Exception) {
                                                Toast.makeText(this@CustomSettingsActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                            }
                                        } else {
                                            Toast.makeText(this@CustomSettingsActivity, "No previously selected stats export folder found", Toast.LENGTH_LONG).show()
                                        }
                                    } else {
                                        // Ignore unknown commands
                                        success = true
                                    }
                                    success
                                }

                                    if (isRecurring) {
                                        val job = launch {
                                            var nextDelay = delayMillis
                                            while (true) {
                                                kotlinx.coroutines.delay(nextDelay)
                                                task()
                                                val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                                val parsedTime = sdf.parse(executeAtStr)
                                                if (parsedTime != null) {
                                                    val now = java.util.Calendar.getInstance()
                                                    val target = java.util.Calendar.getInstance()
                                                    target.time = parsedTime
                                                    
                                                    val targetCal = java.util.Calendar.getInstance()
                                                    targetCal.set(java.util.Calendar.HOUR_OF_DAY, target.get(java.util.Calendar.HOUR_OF_DAY))
                                                    targetCal.set(java.util.Calendar.MINUTE, target.get(java.util.Calendar.MINUTE))
                                                    targetCal.set(java.util.Calendar.SECOND, 0)
                                                    targetCal.set(java.util.Calendar.MILLISECOND, 0)
                                                    
                                                    if (targetCal.before(now) || targetCal.timeInMillis <= now.timeInMillis) {
                                                        targetCal.add(java.util.Calendar.DAY_OF_MONTH, 1)
                                                    }
                                                    nextDelay = targetCal.timeInMillis - now.timeInMillis
                                                } else {
                                                    nextDelay = 24 * 60 * 60 * 1000L
                                                }
                                            }
                                        }
                                        pendingCommandJobs.add(job)
                                    } else if (delayMillis > 0) {
                                        val job = launch {
                                            kotlinx.coroutines.delay(delayMillis)
                                            task()
                                        }
                                        pendingCommandJobs.add(job)
                                    } else {
                                        val success = task()
                                        if (!success) {
                                            android.util.Log.e("CustomSettings", "Command sequence aborted at $command $data")
                                            Toast.makeText(this@CustomSettingsActivity, "Command sequence aborted at: $command $data", Toast.LENGTH_SHORT).show()
                                            break
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("CustomSettings", "Error importing commands", e)
                            Toast.makeText(this@CustomSettingsActivity, "Error importing commands: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

    private suspend fun importStateFromJson(json: JSONObject) = withContext(Dispatchers.IO) {
        if (json.has("status")) {
            val status = json.getString("status")
            val vpnState = VpnController.state()
            android.util.Log.i("CustomSettings", "Importing status: $status (current: ${vpnState.on})")
            if (status == "started" && !vpnState.on) {
                VpnController.start(this@CustomSettingsActivity)
            } else if (status == "stopped" && vpnState.on) {
                VpnController.stop("State imported from JSON", this@CustomSettingsActivity)
            }
        }

        if (json.has("universal")) {
            val universal = json.getJSONObject("universal")
            android.util.Log.i("CustomSettings", "Importing universal rules")
            if (universal.has("blockWhenDeviceLocked")) persistentState.setBlockWhenDeviceLocked(universal.getBoolean("blockWhenDeviceLocked"))
            if (universal.has("blockAppWhenBackground")) persistentState.setBlockAppWhenBackground(universal.getBoolean("blockAppWhenBackground"))
            if (universal.has("udpBlocked")) persistentState.setUdpBlocked(universal.getBoolean("udpBlocked"))
            if (universal.has("blockUnknownConnections")) persistentState.setBlockUnknownConnections(universal.getBoolean("blockUnknownConnections"))
            if (universal.has("disallowDnsBypass")) persistentState.setDisallowDnsBypass(universal.getBoolean("disallowDnsBypass"))
            if (universal.has("blockNewlyInstalledApp")) persistentState.setBlockNewlyInstalledApp(universal.getBoolean("blockNewlyInstalledApp"))
            if (universal.has("blockMeteredConnections")) persistentState.setBlockMeteredConnections(universal.getBoolean("blockMeteredConnections"))
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
    }

    private suspend fun importCustomIps(array: JSONArray, isUniversal: Boolean) {
        val currentRules = customIpRepository.getIpRules().filter { 
            if (isUniversal) it.uid == com.celzero.bravedns.util.Constants.UID_EVERYBODY else it.uid != com.celzero.bravedns.util.Constants.UID_EVERYBODY 
        }
        android.util.Log.i("CustomSettings", "Importing ${array.length()} CustomIp rules (Universal: $isUniversal)")
        
        for (i in 0 until array.length()) {
            val ruleJson = array.getJSONObject(i)
            val ipAddress = ruleJson.getString("ipAddress")
            val port = ruleJson.getInt("port")
            val uid = ruleJson.getInt("uid")
            val protocol = ruleJson.getString("protocol")
            val isActive = ruleJson.getBoolean("isActive")
            val status = ruleJson.getInt("status")
            
            val existing = currentRules.find { it.ipAddress == ipAddress && it.port == port && it.uid == uid && it.protocol == protocol }
            if (existing == null) {
                val newRule = com.celzero.bravedns.database.CustomIp()
                newRule.uid = uid
                newRule.ipAddress = ipAddress
                newRule.port = port
                newRule.protocol = protocol
                newRule.isActive = isActive
                newRule.status = status
                customIpRepository.insert(newRule)
                android.util.Log.i("CustomSettings", "Inserted CustomIp: $ipAddress:$port (uid: $uid)")
            } else if (existing.isActive != isActive || existing.status != status) {
                existing.isActive = isActive
                existing.status = status
                customIpRepository.update(existing)
                android.util.Log.i("CustomSettings", "Updated CustomIp: $ipAddress:$port (uid: $uid)")
            }
        }
    }

    private suspend fun importCustomDomains(array: JSONArray, isUniversal: Boolean) {
        val currentRules = customDomainRepository.getAllCustomDomains().filter { 
            if (isUniversal) it.uid == com.celzero.bravedns.util.Constants.UID_EVERYBODY else it.uid != com.celzero.bravedns.util.Constants.UID_EVERYBODY 
        }
        android.util.Log.i("CustomSettings", "Importing ${array.length()} CustomDomain rules (Universal: $isUniversal)")
        
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
                android.util.Log.i("CustomSettings", "Inserted CustomDomain: $domain (uid: $uid)")
            } else if (existing.status != status || existing.type != type || existing.ips != ips) {
                val newRule = com.celzero.bravedns.database.CustomDomain(null)
                newRule.domain = domain
                newRule.uid = uid
                newRule.status = status
                newRule.type = type
                newRule.ips = ips
                customDomainRepository.update(existing, newRule)
                android.util.Log.i("CustomSettings", "Updated CustomDomain: $domain (uid: $uid)")
            }
        }
    }

    private fun showAppLockOptionsDialog() {
        val prefs = getSharedPreferences("RethinkPinLock", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("ENABLE_PIN", true)
        
        val options = arrayOf(
            if (isEnabled) "Disable PIN" else "Enable PIN",
            "Change PIN"
        )
        
        AlertDialog.Builder(this)
            .setTitle("App Lock Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { // Toggle enable/disable
                        prefs.edit().putBoolean("ENABLE_PIN", !isEnabled).apply()
                        Toast.makeText(this, if (!isEnabled) "PIN Enabled" else "PIN Disabled", Toast.LENGTH_SHORT).show()
                    }
                    1 -> { // Change PIN
                        showChangePinDialog()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showChangePinDialog() {
        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(48, 16, 48, 8)

        val etCurrent = android.widget.EditText(this)
        etCurrent.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        etCurrent.hint = "Current PIN"
        layout.addView(etCurrent)

        val etNew = android.widget.EditText(this)
        etNew.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        etNew.hint = "New PIN"
        layout.addView(etNew)

        val etConfirm = android.widget.EditText(this)
        etConfirm.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        etConfirm.hint = "Confirm New PIN"
        layout.addView(etConfirm)

        val changePinDialog = AlertDialog.Builder(this)
            .setTitle("Change PIN")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
            
        changePinDialog.show()
        changePinDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val prefs = getSharedPreferences("RethinkPinLock", Context.MODE_PRIVATE)
            val savedPin = prefs.getString("PIN", "123456")
            val current = etCurrent.text.toString().trim()
            val newPin = etNew.text.toString().trim()
            val confirm = etConfirm.text.toString().trim()

            if (current != savedPin) {
                etCurrent.error = "Incorrect current PIN"
                return@setOnClickListener
            }
            if (newPin.isEmpty()) {
                etNew.error = "PIN cannot be empty"
                return@setOnClickListener
            }
            if (newPin != confirm) {
                etConfirm.error = "PINs do not match"
                return@setOnClickListener
            }

            prefs.edit().putString("PIN", newPin).apply()
            Toast.makeText(this, "PIN updated successfully", Toast.LENGTH_SHORT).show()
            changePinDialog.dismiss()
        }
    }
    
    private fun findFileUriInTree(treeUri: android.net.Uri, displayName: String): android.net.Uri? {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        try {
            contentResolver.query(
                childrenUri,
                arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (displayName == cursor.getString(nameCol)) {
                        return android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idCol))
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CustomSettings", "Failed to query tree children", e)
        }
        return null
    }
}
