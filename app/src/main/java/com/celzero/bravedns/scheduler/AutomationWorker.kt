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
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_SCHEDULER

class AutomationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Logger.i(LOG_TAG_SCHEDULER, "AutomationWorker starting periodic catch-up check")
        return try {
            AutomationManager.checkAndRunCatchUp(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_SCHEDULER, "AutomationWorker error during catch-up: ${e.message}", e)
            Result.retry()
        }
    }
}
