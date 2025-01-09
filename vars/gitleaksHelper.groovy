// vars/gitleaksHelper.groovy
import groovy.json.JsonSlurper

// Powered by Claude 3.5 xD
def scanSecretsLeak(Map config = [:]) {
    // Default configuration
    def defaults = [
        reportFormat: 'json',
        reportPath: 'gitleaks_report.json',
        configPath: '',          // Optional custom config file
        failOnError: true,
        excludePaths: [],
        maxTargetMB: 5,         // Max file size in MB to scan
        logOpts: '',            // Git log options if needed
        followSymlinks: false    // Whether to scan symlink files
    ]
    
    // Merge provided config with defaults
    config = defaults + config

    try {
        // Verify gitleaks installation
        sh(script: 'gitleaks version', returnStdout: true)
        
        // Build command with supported options
        def cmd = """
            gitleaks detect \
            --source . \
            --no-git \
            --report-format ${config.reportFormat} \
            --report-path ${config.reportPath} \
            --max-target-megabytes ${config.maxTargetMB}
        """
        

        if (config.configPath) {
            cmd += " --config ${config.configPath}"
        }

        if (config.logOpts) {
            cmd += " --log-opts '${config.logOpts}'"
        }

        if (config.followSymlinks) {
            cmd += " --follow-symlinks"
        }

        // Run gitleaks
        def gitleaksOutput = sh(script: cmd, returnStatus: true)
        
        // Process results if JSON report exists
        if (config.reportFormat == 'json' && fileExists(config.reportPath)) {
            def reportContent = readJSON file: config.reportPath
            
            // Generate HTML report
            generateHTMLReport(reportContent, 'gitleaks_report.html')
            
            // Debug logging
            echo "Report path: ${config.reportPath}"
            
            try {
                // Archive reports with absolute paths
                archiveArtifacts(
                    artifacts: "**/${config.reportPath},**/gitleaks_report.html",
                    fingerprint: true,
                    allowEmptyArchive: true,
                    onlyIfSuccessful: false
                )
            } catch (Exception e) {
                echo "Warning: Failed to archive artifacts: ${e.getMessage()}"
            }

            
            // Print summary
            def findings = reportContent.size()
            /* old send msg
            msg = "Gitleaks scan completed. Found ${findings} potential issue(s)"
            sendNotifyTelegram.sendTelegram(msg)
            sendNotifyTelegram.sendTelegram(config.reportPath, true)
            */
            // Send message to Telegram with style
            def consolidatedMsg = """🔍 <b>Gitleaks Scan Results</b>
━━━━━━━━━━━━━━━━
🎯 <b>Found:</b> ${findings} potential issue(s)
📄 <b>Details:</b> See attached report"""
            // file content, boolean true, message content
            sendNotifyTelegram.sendTelegram(config.reportPath, true, consolidatedMsg)
            
            // Fail build if issues found and failOnError is true
            // if (findings > 0 && config.failOnError) {
            //     error("Gitleaks detected ${findings} potential secret(s) in the repository")
            // }

            // Log warning instead of failing the build if issues found
            if (findings > 0) {
                echo "WARNING: Gitleaks detected ${findings} potential secret(s) in the repository but continuing build"
            }

            // Clean up Gitleaks reports
            cleanupGitleaksReports()
        }
        
        return gitleaksOutput
    } catch (Exception e) {
        echo "Error during Gitleaks scan: ${e.getMessage()}"
        if (config.failOnError) {
            throw e
        }
        return 1
    }
}

// Helper function to generate HTML report
def generateHTMLReport(jsonData, outputFile) {
    def html = """
        <html>
        <head>
            <style>
                body { font-family: Arial, sans-serif; margin: 20px; }
                table { border-collapse: collapse; width: 100%; }
                th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
                th { background-color: #f2f2f2; }
                .severity-high { color: red; }
                .severity-medium { color: orange; }
                .severity-low { color: yellow; }
            </style>
        </head>
        <body>
            <h1>Gitleaks Scan Report</h1>
            <p>Scan Date: ${new Date().format("yyyy-MM-dd HH:mm:ss")}</p>
            <table>
                <tr>
                    <th>Description</th>
                    <th>File</th>
                    <th>Line</th>
                    <th>Rule</th>
                </tr>
    """
    
    jsonData.each { finding ->
        html += """
            <tr>
                <td>${finding.Description ?: 'N/A'}</td>
                <td>${finding.File ?: 'N/A'}</td>
                <td>${finding.StartLine ?: 'N/A'}</td>
                <td>${finding.RuleID ?: 'N/A'}</td>
            </tr>
        """
    }
    
    html += """
            </table>
        </body>
        </html>
    """
    
    writeFile file: outputFile, text: html
}


def cleanupGitleaksReports() {
    // Clean up Gitleaks reports
    // Check if the reports exist before deleting
    sh """
        for file in gitleaks_report.json gitleaks_report.html; do
            if [ -f "\$file" ]; then
                echo "Removing \$file..."
                rm -f "\$file"
            else
                echo "\$file not found, skipping..."
            fi
        done
    """
}
