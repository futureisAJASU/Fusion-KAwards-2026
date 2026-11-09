package com.projectnuke.fusion.ui

import android.app.Activity
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Keeps device UI failures distinguishable from failures in the tested surface. */
internal fun composeHostTestRules(compose: ComposeContentTestRule): TestRule =
    RuleChain.outerRule(DeviceUiPreflightRule(compose))
        .around(compose)
        .around(ComposeRootFailureDiagnosticsRule(compose))

/** Call after content setup so every surface assertion starts with a resumed test Activity. */
internal fun ComposeContentTestRule.setContentOnResumedTestHost(content: @Composable () -> Unit) {
    setContent(content)
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    var resumedTestActivity = false
    instrumentation.runOnMainSync {
        resumedTestActivity = ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .any { it.packageName == instrumentation.targetContext.packageName }
    }
    check(resumedTestActivity) {
        "NO_RESUMED_TEST_ACTIVITY: setContent returned without a resumed target Activity"
    }
}

private class DeviceUiPreflightRule(
    private val compose: ComposeContentTestRule,
) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive == true
            val keyguardLocked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
            if (!interactive || keyguardLocked) {
                emitComposeHostDiagnostics(
                    description,
                    compose,
                    forcedReason = "DEVICE_LOCKED",
                    contentWasSet = false,
                )
                throw AssertionError(
                    "DEVICE_LOCKED: wake and unlock the device before running Compose UI qualification " +
                        "(interactive=$interactive, keyguardLocked=$keyguardLocked)",
                )
            }
            base.evaluate()
        }
    }
}

private class ComposeRootFailureDiagnosticsRule(
    private val compose: ComposeContentTestRule,
) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            try {
                base.evaluate()
            } catch (failure: Throwable) {
                val missingComposeRoot = failure.causes().any { cause ->
                        cause.message.orEmpty().contains("No compose hierarchies found") ||
                            cause.message.orEmpty().contains("NO_RESUMED_TEST_ACTIVITY")
                    }
                if (missingComposeRoot) {
                    runCatching {
                        emitComposeHostDiagnostics(
                            description,
                            compose,
                            forcedReason = null,
                            contentWasSet = true,
                        )
                    }.onFailure(failure::addSuppressed)
                } else if (failure.causes().any { it is AssertionError }) {
                    val screenshot = saveFailureScreenshot(description)
                    val message = "COMPOSE_TEST_ASSERTION_FAILURE ${description.className}#${description.methodName}" +
                        " screenshot=${screenshot.path ?: "unavailable"}" +
                        screenshot.error?.let { " screenshotError=$it" }.orEmpty()
                    Log.e("ComposeHostDiagnostics", message.take(1_000))
                    System.err.println(message.take(1_000))
                }
                throw failure
            }
        }
    }
}

private fun emitComposeHostDiagnostics(
    description: Description,
    compose: ComposeContentTestRule,
    forcedReason: String?,
    contentWasSet: Boolean,
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val targetContext = instrumentation.targetContext
    val testContext = instrumentation.context
    val power = targetContext.getSystemService(PowerManager::class.java)
    val keyguard = targetContext.getSystemService(KeyguardManager::class.java)
    val interactive = power?.isInteractive == true
    val keyguardLocked = keyguard?.isKeyguardLocked == true
    val targetPackage = targetContext.packageName
    val instrumentationPackage = testContext.packageName
    val stages = listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED)
    val activities = mutableListOf<String>()
    var resumedActivityCount = 0
    var resumedTargetCount = 0
    var anyTargetActivity = false
    var allResumedTargetWindowsFocused = false
    instrumentation.runOnMainSync {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        resumedActivityCount = monitor.getActivitiesInStage(Stage.RESUMED).count()
        stages.forEach { stage ->
            monitor.getActivitiesInStage(stage).forEach { activity: Activity ->
                val isTarget = activity.packageName == targetPackage
                if (isTarget) {
                    anyTargetActivity = true
                    if (stage == Stage.RESUMED) resumedTargetCount++
                }
                activities += "${stage.name}:${activity.componentName.flattenToShortString()}" +
                    " windowFocused=${activity.window.decorView.hasWindowFocus()}" +
                    " attached=${activity.window.decorView.isAttachedToWindow}"
            }
        }
        allResumedTargetWindowsFocused = monitor.getActivitiesInStage(Stage.RESUMED)
            .filter { it.packageName == targetPackage }
            .all { it.window.decorView.hasWindowFocus() }
    }
    val activeRoot = runCatching { instrumentation.uiAutomation.rootInActiveWindow }
        .getOrNull()
    val activePackage = activeRoot?.packageName?.toString()
    val activeClass = activeRoot?.className?.toString()
    val rootProbe = if (!contentWasSet) {
        "NOT_RUN_BEFORE_CONTENT"
    } else {
        runCatching {
            val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            val visibleRoots = roots.filter { it.size.width > 0 && it.size.height > 0 }
            if (visibleRoots.isNotEmpty()) {
                "PRESENT(count=${roots.size}, visible=${visibleRoots.size}, sizes=${visibleRoots.map { it.size }})"
            } else {
                "NO_COMPOSE_ROOT_AFTER_SET_CONTENT(count=${roots.size}, sizes=${roots.map { it.size }})"
            }
        }.fold(
            onSuccess = { it },
            onFailure = { "NO_COMPOSE_ROOT_AFTER_SET_CONTENT (${it.javaClass.simpleName})" },
        )
    }
    val reason = forcedReason ?: when {
        !interactive || keyguardLocked -> "DEVICE_LOCKED"
        activePackage != null && activePackage != targetPackage && activePackage != instrumentationPackage ->
            "EXTERNAL_ACTIVITY_FOREGROUND"
        resumedTargetCount == 0 && anyTargetActivity -> "TEST_ACTIVITY_BACKGROUND"
        resumedTargetCount == 0 -> "NO_RESUMED_TEST_ACTIVITY"
        !allResumedTargetWindowsFocused && rootProbe.startsWith("NO_COMPOSE_ROOT") -> "WINDOW_NOT_FOCUSED"
        rootProbe.startsWith("NO_COMPOSE_ROOT") -> "NO_COMPOSE_ROOT_AFTER_SET_CONTENT"
        else -> "NO_COMPOSE_ROOT_AFTER_SET_CONTENT"
    }

    val windowState = shellLines("dumpsys window", maxLines = 400, maxChars = 24_000)
        .lineSequence()
        .filter { line ->
            line.contains("mCurrentFocus=") || line.contains("mFocusedApp=") ||
                line.contains("mTopFullscreenOpaqueWindowState=") || line.contains("mKeyguardShowing=") ||
                line.contains("mDreamingLockscreen=")
        }
        .take(12)
        .joinToString("\n")
    val powerState = shellLines("dumpsys power", maxLines = 120, maxChars = 8_000)
        .lineSequence()
        .filter { it.contains("mWakefulness=") || it.contains("mInteractive=") || it.contains("Display Power:") }
        .take(8)
        .joinToString("\n")
    val activityState = shellLines("dumpsys activity activities", maxLines = 500, maxChars = 24_000)
        .lineSequence()
        .filter { it.contains("ResumedActivity:") || it.contains("topResumedActivity=") || it.contains("mResumedActivity:") }
        .take(8)
        .joinToString("\n")
    val recentLogcat = shellLines("logcat -d -t 180", maxLines = 180, maxChars = 32_000)
        .lineSequence()
        .filter { line ->
            listOf("ActivityTaskManager", "ActivityManager", "WindowManager", "InputDispatcher", "TestRunner", "LifecycleMonitor")
                .any(line::contains) || line.contains(targetPackage) || line.contains(instrumentationPackage)
        }
        .toList()
        .takeLast(60)
        .joinToString("\n")
    val screenshotPath = saveFailureScreenshot(description)
    val report = buildString {
        appendLine("COMPOSE_HOST_DIAGNOSTICS_BEGIN")
        appendLine("reason=$reason")
        appendLine("deviceInteractive=$interactive keyguardLocked=$keyguardLocked")
        appendLine("targetPackage=$targetPackage instrumentationPackage=$instrumentationPackage pid=${android.os.Process.myPid()}")
        appendLine("resumedActivityCount=$resumedActivityCount resumedTargetActivities=$resumedTargetCount anyTargetActivity=$anyTargetActivity")
        appendLine("activeWindow=${activePackage ?: "unavailable"}/${activeClass ?: "unavailable"} rootProbe=$rootProbe")
        appendLine("activities=${activities.take(12).joinToString(" | ").take(3_000)}")
        appendLine("windowState=${windowState.take(4_000)}")
        appendLine("powerState=${powerState.take(1_200)}")
        appendLine("activityState=${activityState.take(1_200)}")
        appendLine("screenshot=${screenshotPath.path ?: "unavailable"}${screenshotPath.error?.let { " error=$it" }.orEmpty()}")
        appendLine("recentLogcat=${recentLogcat.take(8_000)}")
        append("COMPOSE_HOST_DIAGNOSTICS_END")
    }
    Log.e("ComposeHostDiagnostics", report.take(16_000))
    System.err.println(report.take(16_000))
}

private fun shellLines(command: String, maxLines: Int, maxChars: Int): String = runCatching {
    val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader(StandardCharsets.UTF_8).use { reader ->
        val lines = ArrayList<String>(maxLines)
        var remainingChars = maxChars
        while (lines.size < maxLines && remainingChars > 0) {
            val line = reader.readLine() ?: break
            val boundedLine = line.take(remainingChars)
            lines += boundedLine
            remainingChars -= boundedLine.length + 1
        }
        lines.joinToString("\n")
    }
}.getOrDefault("<unavailable: $command>")

private data class ScreenshotResult(val path: String?, val error: String? = null)

private fun saveFailureScreenshot(description: Description): ScreenshotResult = try {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val bitmap = instrumentation.uiAutomation.takeScreenshot()
        ?: return ScreenshotResult(null, "UiAutomation.takeScreenshot returned null")
    val safeName = "${description.className.substringAfterLast('.')}-${description.methodName}"
        .replace(Regex("[^A-Za-z0-9_.-]"), "_")
        .take(100)
    val output = File(instrumentation.context.cacheDir, "compose-failures/$safeName-${System.currentTimeMillis()}.png")
    output.parentFile?.mkdirs()
    output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    ScreenshotResult(output.absolutePath)
} catch (failure: Exception) {
    ScreenshotResult(null, "${failure.javaClass.simpleName}: ${failure.message.orEmpty().take(240)}")
}

private fun Throwable.causes(): Sequence<Throwable> = sequence {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = this@causes
    repeat(8) {
        val cause = current ?: return@sequence
        if (!seen.add(cause)) return@sequence
        yield(cause)
        current = cause.cause
    }
}
