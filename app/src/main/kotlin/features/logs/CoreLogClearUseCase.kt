package features.logs

import android.content.Context
import engine.xray.XrayCoreLogPaths
import engine.xray.clearCoreLogFilesAsApp
import engine.xray.prepareXrayCoreLogPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class CoreLogClearUseCase(
    context: Context,
) {
    private val appContext = context.applicationContext

    suspend fun clear(logFile: XrayLogFile) {
        val logPath = appContext.prepareXrayCoreLogPaths().pathOf(logFile)
        if (logPath.isBlank()) {
            return
        }

        withContext(Dispatchers.IO) {
            clearCoreLogFilesAsApp(
                logPaths = listOf(logPath),
                logTag = LogTag,
            )
        }
    }

    private fun XrayCoreLogPaths.pathOf(logFile: XrayLogFile): String {
        return when (logFile) {
            XrayLogFile.Error -> errorLogPath
            XrayLogFile.Access -> accessLogPath
        }
    }

    private companion object {
        const val LogTag = "CoreLogClearUseCase"
    }
}

internal enum class XrayLogFile {
    Error,
    Access,
}
