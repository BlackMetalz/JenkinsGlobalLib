// Ref https://github.com/BlackMetalz/Platforms/blob/master/Jenkins/groovy/basic.md
// https://stackoverflow.com/questions/62596007/jenkins-secret-text-credential-as-variable-in-pipeline-script
// vars/sendNotifyTelegram.groovy
// Usage
/*
    - with msg
    sendNotifyTelegram(msg: "Hello from Jenkins")
    - without msg
    sendNotifyTelegram()
*/

def call(Map config = [:]) {
    // map status with Emoji
    /*
    success_emoji = "\u2705"  # ✅ Checkmark for success
    failure_emoji = "\u274C"  # ❌ Cross Mark for failure
    aborted_emoji = "\u23F9"  # ⏹️ Stop Button for aborted
    rocket_emoji: \U0001F680 (Perfect for launching a new build) 🚀
    "DOMAIN": "\uD83C\uDF10"  // 🌐 (globe emoji for domain/URL)

    */
    Map buildStatus = [
        "SUCCESS": "\u2705", // ✅
        "FAILURE": "\u274C", // ❌
        "ABORTED": "\u23F9", // ⏹️
        "UNSTABLE": "\u26A0", // ⚠️
        "NOT_BUILT": "\uD83D\uDEA7", // 🚧
        "STARTED": "\uD83D\uDE80" // 🚀
    ]
    
    //  Need to wrap because without this, env will be null. Example with null: env.BUILD_USER: Started by null >.>
    wrap([$class: 'BuildUser']) {
        buildUser = "${env.BUILD_USER}" ?: "Trigger by webhook"
    }
    // map status with Emoji xD
    String buildResult_emoji = buildStatus[currentBuild.result]
    // Init buildInfo with ENV - JOB - Build URL. We can use this later without re-declare
    // Build info template with consistent formatting
    String buildInfo = """ENV: <b>${env.DEPLOY_ENV}</b>
ACTION: <b>${env.ACTION}</b>
JOB: <b>${env.JOB_NAME}</b>
BUILD NUMBER: <b><a href="${env.BUILD_URL}">#${env.BUILD_NUMBER}</a></b>"""

    
    // Start build message
    String buildStartInfo = """${buildStatus["STARTED"]} <b>Build Started</b>
━━━━━━━━━━━━━━━━
${buildInfo}
👤 Started by: <b>${buildUser}</b>""".stripIndent()


    // Ingress config
    // Declare ingressInfo with default value
    String ingressInfo = "" // Default value
    if (config.ingressConfig) {
        ingressInfo = """🌐<b>Domain internal K8S:</b> <a href="${config.ingressConfig.host}">${config.ingressConfig.host}</a>""".stripIndent()
    }

    // Init msg with default value
    def commitMsg = env.GIT_COMMIT_MSG ?: sh(script: 'git log -1 --pretty=%B', returnStdout: true).trim()
    if (commitMsg.length() > 100) {
        commitMsg = commitMsg.take(97) + "..."
    }

    // Build end message
    String buildEndInfo = """📋 <b>Build Report</b>
━━━━━━━━━━━━━━━━
${buildInfo}
📊 <b>Status:</b> ${buildResult_emoji} ${currentBuild.result} 
⏱️ <b>Duration:</b> ${currentBuild.durationString}
📝 <b>Commit:</b> ${commitMsg}""".stripIndent()
    

    if (config.msg != null) {
        msg = config.msg
    } else if (config.buildType == "start") {
        msg = buildStartInfo
    } else if (config.ingressConfig) {
        msg = ingressInfo
    } else {
        msg = buildEndInfo
    }

    // echo "Sending message to Telegram with msg: ${msg}"
    sendTelegram(msg)
}

/*
def sendTelegram(message) {
    int retries = 5
    // get credentials ID from Jenkins
    withCredentials([
        string(credentialsId: 'telegram_group_id', variable: 'chatId'),
        string(credentialsId: 'telegram_group_token', variable: 'botToken')
    ]) {
        def encodedMessage = URLEncoder.encode(message, 'UTF-8')
        def url_formatted = String.format('https://api.telegram.org/bot%s/sendMessage?text=%s&chat_id=%s&disable_web_page_preview=true&parse_mode=HTML',
  botToken, encodedMessage, chatId)
        for (int i = 0; i < retries; i++) {
            try {
                response = httpRequest(consoleLogResponseBody: false,
                                        quiet: true,
                                        contentType: 'APPLICATION_JSON',
                                        httpMode: 'GET',
                                        url: url_formatted,
                                        validResponseCodes: '200')
                return response
            } catch (Exception e) {
                if (i < retries - 1) {
                    echo 'Failed to send message to Telegram, retrying in 5 seconds'
                    sleep 5
                } else {
                    echo "Failed to send message to Telegram after ${retries} attempts"
                    throw e
                }
            }
        }
    }
}
*/

/*
// Send text message
sendTelegram("Hello World")

// Send file
sendTelegram("/path/to/file.txt", true)
*/

def sendTelegram(content, Boolean isContentFile = false, String msgContent = null) {
    int retries = 5
    withCredentials([
        string(credentialsId: 'telegram_group_id', variable: 'chatId'),
        string(credentialsId: 'telegram_group_token', variable: 'botToken')
    ]) {
        if (isContentFile && msgContent == null) {
            return sendFile(content, chatId, botToken, retries)
        } else if (isContentFile && msgContent != null) {
            return sendFile(content, chatId, botToken, retries, msgContent)
        }
        else {
            return sendMessage(content, chatId, botToken, retries)
        }
    }
}

private def sendMessage(message, chatId, botToken, retries) {
    def encodedMessage = URLEncoder.encode(message, 'UTF-8')
    def url = String.format('https://api.telegram.org/bot%s/sendMessage?text=%s&chat_id=%s&disable_web_page_preview=true&parse_mode=HTML',
        botToken, encodedMessage, chatId)
    
    return makeRequest(url, retries)
}

private def sendFile(filePath, chatId, botToken, retries, msgContent = null) {
    if (!fileExists(filePath)) {
        error("File not found: ${filePath}")
    }

    // To prevent Groovy String interpolation warning.
    // Build command as a string using StringBuilder
    def cmd = new StringBuilder()
    cmd.append("set +x\n")
    cmd.append("curl -s -X POST ")
    cmd.append("'https://api.telegram.org/bot${botToken}/sendDocument' ")
    cmd.append("-F 'chat_id=${chatId}' ")
    cmd.append("-F 'document=@${filePath}' ")

    if (msgContent != null) {
        cmd.append("-F 'caption=${msgContent}' ")
        cmd.append("-F 'parse_mode=HTML'")
    }

    for (int i = 0; i < retries; i++) {
        try {
            def response = sh(
                script: cmd.toString(),
                returnStdout: true
            )
            return response
        } catch (Exception e) {
            if (i < retries - 1) {
                echo 'Failed to send file to Telegram, retrying in 5 seconds'
                sleep 5
            } else {
                echo "Failed to send file to Telegram after ${retries} attempts"
                throw e
            }
        }
    }
}

private def makeRequest(url, retries) {
    for (int i = 0; i < retries; i++) {
        try {
            response = httpRequest(
                consoleLogResponseBody: false,
                quiet: true,
                contentType: 'APPLICATION_JSON',
                httpMode: 'GET',
                url: url,
                validResponseCodes: '200'
            )
            return response
        } catch (Exception e) {
            if (i < retries - 1) {
                echo 'Failed to send to Telegram, retrying in 5 seconds'
                sleep 5
            } else {
                echo "Failed to send to Telegram after ${retries} attempts"
                throw e
            }
        }
    }
}


def sendDeploymentInfo(resourceConfig, ingressConfig = null) {
    def deploymentInfo = """📦 <b>Deployment Info</b>
━━━━━━━━━━━━━━━━
📊 <b>Resources</b>
├─ Replicas: ${resourceConfig.replicaCount}
├─ Memory Request: ${resourceConfig.memoryRequest}
├─ CPU Request: ${resourceConfig.cpuRequest}
├─ Memory Limit: ${resourceConfig.memoryLimit}
└─ CPU Limit: ${resourceConfig.cpuLimit}""".stripIndent()

    if (ingressConfig) {
        deploymentInfo += """\n\n🌐 <b>Access</b>
└─ Internal K8S: <a href="${ingressConfig.host}">${ingressConfig.host}</a>""".stripIndent()
    }

    sendNotifyTelegram(msg: deploymentInfo)
}