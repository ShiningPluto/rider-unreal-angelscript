package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.AppExecutorUtil
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.scriptacus.riderunrealangelscript.lang.AngelScriptLanguage
import com.scriptacus.riderunrealangelscript.settings.AngelScriptLspSettings
import com.scriptacus.riderunrealangelscript.settings.AngelScriptLspSettingsSync
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Starts the LSP server when a project opens, so AngelScript analysis is available before the first
 * `.as` file is opened.
 *
 * Configuration itself is pushed by [AngelScriptLanguageClient] on every server start; this activity
 * only makes sure a server exists to push to, retrying with exponential backoff while it comes up.
 */
class AngelScriptLspInitializer : ProjectActivity {
    private val LOG = Logger.getInstance(AngelScriptLspInitializer::class.java)

    override suspend fun execute(project: Project) {
        val settings = AngelScriptLspSettings.getInstance().state
        val initialDelay = settings.lspInitialDelayMs.toLong()
        val maxRetries = settings.lspMaxRetries
        val backoffBase = settings.lspRetryBackoffMs.toLong()

        LOG.info("Scheduling LSP initialization for project: ${project.name} (initial delay: ${initialDelay}ms, max retries: $maxRetries)")

        // Schedule the first attempt
        scheduleSettingsSync(project, initialDelay, maxRetries, backoffBase, AtomicInteger(0))
    }

    private fun scheduleSettingsSync(
        project: Project,
        delay: Long,
        maxRetries: Int,
        backoffBase: Long,
        attemptCounter: AtomicInteger
    ) {
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            val attempt = attemptCounter.incrementAndGet()
            LOG.info("Checking AngelScript LSP server startup (attempt $attempt/${maxRetries + 1})")

            // Check if LSP server is ready
            val serverStatus = LanguageServerManager.getInstance(project)
                .getServerStatus(AngelScriptLanguageServerFactory.SERVER_ID)

            val isLspReady = serverStatus == com.redhat.devtools.lsp4ij.ServerStatus.started

            if (isLspReady || attempt > maxRetries) {
                if (isLspReady) {
                    LOG.info("LSP server is ready (status: $serverStatus), re-sending configuration")
                } else {
                    LOG.info("LSP server still not started after $attempt attempts (status: $serverStatus), starting it now")
                }
                // Also starts the server when it is not running yet; the client pushes the
                // configuration again once the server reports 'started'.
                AngelScriptLspSettingsSync(project).syncSettingsToLsp()
            } else {
                // Calculate exponential backoff: backoffBase * (2^(attempt-1))
                val nextDelay = backoffBase * (1 shl (attempt - 1))
                LOG.info("LSP server not ready yet (status: $serverStatus), retrying in ${nextDelay}ms (attempt ${attempt + 1}/${maxRetries + 1})")
                scheduleSettingsSync(project, nextDelay, maxRetries, backoffBase, attemptCounter)
            }
        }, delay, TimeUnit.MILLISECONDS)
    }
}