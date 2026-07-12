// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root

import utils.shellQuote

internal fun <Config : RootModeStartConfig> Config.buildInstallRootBootScriptCommand(
    modeName: String,
    buildSetupRulesCommand: (Config) -> String,
    buildPostCoreStartCommand: (Config) -> String,
    buildReadinessCheck: (Config) -> RootReadinessCheck,
    bootReadinessCheckAttempts: Int,
    appendStartupSummary: StringBuilder.(Config) -> Unit,
    appendStartupFailureDiagnostics: StringBuilder.(Config) -> Unit,
): String {
    val bootScript = root.buildRootBootScript()
    val startupScript = buildRootStartupScript(
        modeName = modeName,
        buildSetupRulesCommand = buildSetupRulesCommand,
        buildPostCoreStartCommand = buildPostCoreStartCommand,
        buildReadinessCheck = buildReadinessCheck,
        bootReadinessCheckAttempts = bootReadinessCheckAttempts,
        appendStartupSummary = appendStartupSummary,
        appendStartupFailureDiagnostics = appendStartupFailureDiagnostics,
    )
    return buildString {
        appendScript("mkdir -p ${RootBootScriptDir.shellQuote()}")
        appendScript("mkdir -p ${root.runtimeLayout.dataDir.shellQuote()}")
        appendScript("chmod 755 ${root.runtimeLayout.stopScriptPath.shellQuote()} || exit 1")
        appendScript("mkdir -p ${root.bootLogDirPath.shellQuote()}")
        appendHeredoc(
            targetPath = root.startupScriptPath,
            content = startupScript,
        )
        appendScript("chmod 755 ${root.startupScriptPath.shellQuote()}")
        appendScript("touch ${root.bootLogPath.shellQuote()}")
        appendScript("chmod 666 ${root.bootLogPath.shellQuote()}")
        appendHeredoc(
            targetPath = RootBootScriptPath,
            content = bootScript,
        )
        appendScript("chmod 755 ${RootBootScriptPath.shellQuote()}")
    }
}

private fun <Config : RootModeStartConfig> Config.buildRootStartupScript(
    modeName: String,
    buildSetupRulesCommand: (Config) -> String,
    buildPostCoreStartCommand: (Config) -> String,
    buildReadinessCheck: (Config) -> RootReadinessCheck,
    bootReadinessCheckAttempts: Int,
    appendStartupSummary: StringBuilder.(Config) -> Unit,
    appendStartupFailureDiagnostics: StringBuilder.(Config) -> Unit,
): String {
    return buildString {
        appendRootStartupPreamble(
            config = this@buildRootStartupScript,
            modeName = modeName,
            appendStartupSummary = appendStartupSummary,
            appendStartupFailureDiagnostics = appendStartupFailureDiagnostics,
        )
        appendScript("# Prepare the native monitor and clear stale runtime markers.")
        appendScript("section \"Prepare asteriskd\"")
        append(root.buildPrepareAsteriskdCommand())
        appendScript("# Create writable Xray log files before starting background processes.")
        appendScript("section \"Prepare core logs\"")
        append(root.coreLogPaths.buildPrepareCoreLogFilesCommand())
        appendScript("# Start Xray before mode-specific helper processes and traffic rules.")
        appendScript("section \"Start Xray-core\"")
        append(root.buildBootStartDaemonCommand())
        val postCoreStartCommand = buildPostCoreStartCommand(this@buildRootStartupScript)
        if (postCoreStartCommand.isNotBlank()) {
            appendScript("# Start the helper process required by $modeName mode.")
            appendScript("section \"Start $modeName helper runtime\"")
            append(postCoreStartCommand)
        }
        rootEbpfConfig?.let { ebpfConfig ->
            appendScript("# Load optional eBPF programs after their userspace dependencies are ready.")
            appendScript("section \"Start $modeName eBPF matcher\"")
            append(ebpfConfig.buildStartCommand())
        }
        appendRootBootReadinessCheck(
            readinessCheck = buildReadinessCheck(this@buildRootStartupScript),
            attempts = bootReadinessCheckAttempts,
        )
        append('\n')
        appendScript("# Install packet-marking and routing rules only after all runtime checks pass.")
        appendScript("section \"Install $modeName rules\"")
        append(buildSetupRulesCommand(this@buildRootStartupScript))
        appendScript("# Start the address monitor after it has rules and BPF maps to maintain.")
        appendScript("section \"Start asteriskd\"")
        append(root.buildStartAsteriskdCommand())
        appendRootBootReadinessCheck(
            readinessCheck = RootReadinessCheck(
                description = "asteriskd",
                command = root.runtimeLayout.buildAsteriskdReadyCommand(),
                failureMessage = "asteriskd did not become ready",
            ),
            attempts = bootReadinessCheckAttempts,
        )
        appendScript("section \"$modeName boot setup is ready\"")
    }
}

private fun StringBuilder.appendRootBootReadinessCheck(
    readinessCheck: RootReadinessCheck,
    attempts: Int,
) {
    val sectionTitle = "Wait for ${readinessCheck.description}".shellQuote()
    val failureMessage = "ERROR: ${readinessCheck.failureMessage}".shellQuote()
    val readinessCommand = readinessCheck.command.indentShellBlock("        ")
    appendScript(
        buildString {
            appendLine("# Poll the runtime because helpers can become ready shortly after they are spawned.")
            appendLine("section $sectionTitle")
            appendLine("runtime_ready=0")
            appendLine("attempt=0")
            appendLine("while [ \"\$attempt\" -lt $attempts ]; do")
            appendLine("    echo \"Attempt \$((attempt + 1))/$attempts\"")
            appendLine("    if (")
            appendLine(readinessCommand)
            appendLine("    ); then")
            appendLine("        runtime_ready=1")
            appendLine("        break")
            appendLine("    fi")
            appendLine("    attempt=\$((attempt + 1))")
            appendLine("    sleep 1")
            appendLine("done")
            appendLine("if [ \"\$runtime_ready\" != \"1\" ]; then")
            appendLine("    echo $failureMessage >&2")
            appendLine("    dump_failure_diagnostics")
            appendLine("    exit 1")
            appendLine("fi")
        },
    )
}

private fun <Config : RootModeStartConfig> StringBuilder.appendRootStartupPreamble(
    config: Config,
    modeName: String,
    appendStartupSummary: StringBuilder.(Config) -> Unit,
    appendStartupFailureDiagnostics: StringBuilder.(Config) -> Unit,
) {
    val modeFailureDiagnostics = buildString {
        appendStartupFailureDiagnostics(config)
    }.trim()
    val modeStartupSummary = buildString {
        appendStartupSummary(config)
    }.trim()
    val failureDiagnosticsFunction = buildString {
        appendLine("dump_failure_diagnostics() {")
        appendLine("    diagnostics_dumped=1")
        appendLine("    echo")
        appendLine("    # Core errors provide the first failure context.")
        appendLine("    echo \"Recent Xray error log:\"")
        appendLine("    tail -n 80 ${config.root.coreLogPaths.errorLogPath.shellQuote()} || true")
        if (modeFailureDiagnostics.isNotBlank()) {
            appendLine("    # Mode-specific helper diagnostics.")
            appendLine(modeFailureDiagnostics.indentShellBlock())
        }
        appendLine("    echo")
        appendLine("    # The monitor log records its last address and interface synchronization.")
        appendLine("    echo \"Asteriskd log:\"")
        appendLine("    tail -n 80 ${config.root.runtimeLayout.asteriskdLogPath.shellQuote()} || true")
        appendLine("    echo")
        appendLine("    # Capture the Xray process identity for stale-PID diagnosis.")
        appendLine("    echo \"Process snapshot:\"")
        appendLine("    pid=\"\$(cat ${config.root.runtimeLayout.pidPath.shellQuote()} || true)\"")
        appendLine("    echo \"pid=\$pid\"")
        appendLine("    if [ -n \"\$pid\" ]; then")
        appendLine("        echo \"cmdline=\$(tr '\\0' ' ' < /proc/\"\$pid\"/cmdline || true)\"")
        appendLine("        echo \"exe=\$(readlink /proc/\"\$pid\"/exe || true)\"")
        appendLine("        grep -E '^(Uid|Gid):' /proc/\"\$pid\"/status || true")
        appendLine("    fi")
        appendLine("}")
    }
    appendScript(
        $$"""
        #!/system/bin/sh
        # Generated by AsteriskNG. This script is invoked by ROOT boot script.

        # Exit immediately on an unhandled command failure. The EXIT trap below
        # writes diagnostics before the boot log is closed.
        set -e
        diagnostics_dumped=0

        timestamp() {
            date '+%Y-%m-%d %H:%M:%S %z' || date
        }

        section() {
            echo
            echo "[$(timestamp)] ===== $* ====="
        }

        finish() {
            rc=$?
            if [ "$rc" != "0" ] && [ "$diagnostics_dumped" != "1" ]; then
                dump_failure_diagnostics
            fi
            echo
            if [ "$rc" = "0" ]; then
                echo "[$(timestamp)] Startup completed successfully"
            else
                echo "[$(timestamp)] Startup failed with exit code $rc"
            fi
        }

        """,
    )
    appendScript(failureDiagnosticsFunction)
    appendScript(
        $$"""
        trap finish EXIT

        # Print the immutable runtime paths before mutating process or rule state.
        echo "AsteriskNG $$modeName startup"
        echo "Started at: $(timestamp)"
        echo "Data dir: $${config.root.runtimeLayout.dataDir.shellQuote()}"
        echo "Config: $${config.root.configPath.shellQuote()}"
        echo "PID file: $${config.root.runtimeLayout.pidPath.shellQuote()}"
        """,
    )
    if (modeStartupSummary.isNotBlank()) {
        appendScript("# Mode-specific runtime settings.")
        appendScript(modeStartupSummary)
    }
    appendScript(
        $$"""
        # Network and logging options used by the generated configuration.
        echo "IPv6 enabled: $${config.root.enableIpv6}"
        echo "System IPv6 disable requested: $${config.root.disableSystemIpv6}"
        echo "Local DNS enabled: $${config.root.enableLocalDns}"
        echo "FakeDNS enabled: $${config.root.enableFakeDns}"
        echo "Access log enabled: $${config.root.enableAccessLog}"
        echo "Core error log: $${config.root.coreLogPaths.errorLogPath.shellQuote()}"
        echo "Core access log: $${config.root.coreLogPaths.accessLogPath.shellQuote()}"
        echo "Asteriskd log: $${config.root.runtimeLayout.asteriskdLogPath.shellQuote()}"
        echo "eBPF rules enabled: $${config.rootEbpfConfig != null}"

        # Clear stale PID metadata and make the executable scripts available to root.
        section "Prepare runtime"
        rm -f $${config.root.runtimeLayout.pidPath.shellQuote()} || true
        chmod 755 $${config.root.runtimeLayout.xrayCorePath.shellQuote()}
        chmod 755 $${config.root.runtimeLayout.stopScriptPath.shellQuote()} || exit 1
        """,
    )
}

internal fun RootStartConfig.buildRootBootScript(): String {
    return buildString {
        appendScript(
            $$"""
            # Generated by AsteriskNG. This script is executed by Magisk service.d at boot.

            (
            until [ "$(getprop sys.boot_completed)" = "1" ]; do
                sleep 1
            done
            until [ -x $${startupScriptPath.shellQuote()} ]; do
                sleep 1
            done

            $${startupScriptPath.shellQuote()} &> $${bootLogPath.shellQuote()}
            ) &
            """,
        )
    }
}
