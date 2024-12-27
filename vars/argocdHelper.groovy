// vars/argocdHelper.groovy
// 14/12/2024 : this only supports helm right now, will make it works with jsonnet later.
import org.jenkins.configs.ArgoCD
import groovy.json.JsonSlurper

def argocdHandler(Map config) {
    withCredentials([string(credentialsId: ArgoCD.ARGOCD_AUTH_TOKEN, variable: 'ARGOCD_AUTH_TOKEN')]) {
        withEnv([
            "ARGOCD_SERVER=${ArgoCD.ARGOCD_URL}",
            'ARGOCD_TOKEN=' + ARGOCD_AUTH_TOKEN, // For Groovy String interpolation fix
            "ARGOCD_USER=${ArgoCD.ARGOCD_USER}"
        ]) 
        {
            // Debug
            // sh "env|sort"
            // Check if the app exists in ArgoCD
            if (checkAppExists(config.appName)) {
                // Sync the app in ArgoCD
                if (config.secret && config.secret != null) {
                    hardRefreshApp(config.appName)
                } else {
                    argocdSyncApp(config.appName)
                }
                
            } else {
                // Create the app in ArgoCD
                if (config.manifestTemplate == 'helm')
                {
                    if (config.secret && config.secret != null) {
                        print("Handling argocd helm with secret")

                        /* This works but we need to use secret for helm values
                        sh """
                            argocd app create ${config.appName} \
                                --repo ${config.GITLAB_MANIFEST_REPO} \
                                --revision ${config.GITLAB_MANIFEST_BRANCH} \
                                --path ${config.appPath} \
                                --project ${config.project} \
                                --dest-server ${config.k8sClusterUrl} \
                                --dest-namespace ${config.namespace} \
                                --upsert \
                                --insecure \
                                --sync-option CreateNamespace=true \
                                --sync-option PruneLast=true \
                                --sync-policy manual \
                                --config-management-plugin argocd-vault-plugin-helm
                        """
                        */
                        
                        
                        writeFile file: "${config.valuesFile}", text: libraryResource('argocd/app_template.yaml')
                        sh """
                            set +x
                            yq eval '.metadata.name = "${config.appName}"' -i ${config.valuesFile}
                            yq eval '.spec.project = "${config.project}"' -i ${config.valuesFile}
                            yq eval '.spec.source.repoURL = "${config.GITLAB_MANIFEST_REPO}"' -i ${config.valuesFile}
                            yq eval '.spec.source.path = "${config.appPath}"' -i ${config.valuesFile}
                            yq eval '.spec.source.targetRevision = "${config.GITLAB_MANIFEST_BRANCH}"' -i ${config.valuesFile}
                            yq eval '.spec.destination.server = "${config.k8sClusterUrl}"' -i ${config.valuesFile}
                            yq eval '.spec.destination.namespace = "${config.namespace}"' -i ${config.valuesFile}
                            # update plugin env
                            yq eval '.spec.source.plugin.env[] |= select(.name == "HELM_ARGS").value = "-f ${config.valuesFile}"' -i ${config.valuesFile}
                            set -x
                        """
                        // sh "cat ${config.valuesFile}"
                        // println("Creating app ${config.appName} in ArgoCD...")
                        k8sHelper.applyManifestFile([
                            manifestFile: config.valuesFile,
                            k8sClusterName: config.argocdClusterConext // Still need to apply to where the ArgoCD is deployed
                        ])
                        
                        
                    } else {
                        println("Handling argocd helm without secret")
                        sh """
                            set +x
                            argocd app create ${config.appName} \
                                --repo ${config.GITLAB_MANIFEST_REPO} \
                                --revision ${config.GITLAB_MANIFEST_BRANCH} \
                                --path ${config.appPath} \
                                --project ${config.project} \
                                --dest-server ${config.k8sClusterUrl} \
                                --dest-namespace ${config.namespace} \
                                --upsert \
                                --insecure \
                                --sync-option CreateNamespace=true \
                                --sync-option PruneLast=true \
                                --sync-policy manual \
                                --values ${config.valuesFile}
                            set -x
                        """
                    }

                    // Sync the app in ArgoCD
                    argocdSyncApp(config.appName)
                }
                else {
                    print("Handling jsonnet")
                }


            }
        }
    }
}

// Check if the app exists in ArgoCD
def checkAppExists(String appName) {
    withCredentials([string(credentialsId: ArgoCD.ARGOCD_AUTH_TOKEN, variable: 'ARGOCD_AUTH_TOKEN')]) {
        withEnv([
            "ARGOCD_SERVER=${ArgoCD.ARGOCD_URL}",
            'ARGOCD_TOKEN=' + ARGOCD_AUTH_TOKEN, // For Groovy String interpolation fix
            "ARGOCD_USER=${ArgoCD.ARGOCD_USER}"
        ]) {
            try {
                int exitCode = sh(
                    script: "argocd app get ${appName} --insecure --grpc-web",
                    returnStatus: true
                )
                
                if (exitCode == 0) {
                    echo "App ${appName} exists in ArgoCD"
                    return true
                } else {
                    echo "App ${appName} does not exist in ArgoCD (exit code: ${exitCode})"
                    return false
                }
            } catch (Exception e) {
                echo "Error checking ArgoCD app: ${e.getMessage()}"
                return false
            }
        }
    }
}

// Sync the app in ArgoCD
def argocdSyncApp(String appName) {
    withCredentials([string(credentialsId: ArgoCD.ARGOCD_AUTH_TOKEN, variable: 'ARGOCD_AUTH_TOKEN')]) {
        withEnv([
            "ARGOCD_SERVER=${ArgoCD.ARGOCD_URL}",
            'ARGOCD_TOKEN=' + ARGOCD_AUTH_TOKEN, // For Groovy String interpolation fix
            "ARGOCD_USER=${ArgoCD.ARGOCD_USER}"
        ]) {
            try {
                sh """
                set +x
                argocd app sync ${appName} --prune --apply-out-of-sync-only --server-side --force --insecure --grpc-web --async
                set -x
                """
                sleep(time: 10, unit: 'SECONDS')
                println("Syncing app ${appName} in ArgoCD...")
            } catch (Exception e) {
                println("Error syncing app ${appName} in ArgoCD: ${e.getMessage()}")
            }
        }
    }
}

// For now we support 2 branch dev and main.
// It wil create application in ArgoCD based on the branch name.
// Example: python-api-app-devenv, python-api-app-production. So we can know which application is for which environment.
def mapBranchToEnvironment(String branchName) {
    switch(branchName) {
        case "dev":
            return "development"
        case "main":
            return "production"
        default:
            return "unknown"
    }
}

// Create application name based on the branch name.
def argocdAppName(String baseName, String branchName) {
    String environment = mapBranchToEnvironment(branchName)
    return "${baseName}-${environment}"
}

// Powered by Claude xD
def argocdHealthChecker(String appName, int maxWaitTime = 300) {
    def startTime = System.currentTimeMillis()
    def retryInterval = 10000 // 10 seconds in milliseconds

    withCredentials([string(credentialsId: ArgoCD.ARGOCD_AUTH_TOKEN, variable: 'ARGOCD_AUTH_TOKEN')]) {
        withEnv([
            "ARGOCD_SERVER=${ArgoCD.ARGOCD_URL}",
            'ARGOCD_TOKEN=' + ARGOCD_AUTH_TOKEN, // For Groovy String interpolation fix
            "ARGOCD_USER=${ArgoCD.ARGOCD_USER}"
        ]) {
            while ((System.currentTimeMillis() - startTime) < maxWaitTime * 1000) { // Convert maxWaitTime to milliseconds
                try {
                    // Execute the ArgoCD command to get application status
                    def syncStatusOutput = sh(
                        script: "argocd app get ${appName} -o json --insecure --grpc-web",
                        returnStdout: true
                    ).trim()

                    // Parse the JSON output using JsonSlurper
                    def jsonParser = new JsonSlurper()
                    def parsedStatus = jsonParser.parseText(syncStatusOutput)

                    // ? is used to avoid `NullPointerException` by safely navigating through potential null references
                    def syncStatus = parsedStatus?.status?.sync?.status
                    def healthStatus = parsedStatus?.status?.health?.status

                    // Check if the app is Synced and Healthy
                    if (syncStatus == "Synced" && healthStatus == "Healthy") {
                        println("✅ ArgoCD app '${appName}' is Synced and Healthy.")
                        return
                    } else {
                        // Lets user know that the app is not yet synced and healthy.
                        println("⏳ App '${appName}' status: Sync = '${syncStatus ?: "Unknown"}', Health = '${healthStatus ?: "Unknown"}'. Retrying in...")
                    }

                } catch (Exception e) {
                    println("⚠️ Error checking ArgoCD app '${appName}': ${e.message}. Retrying in 5 seconds...")
                }

                sleep(time: retryInterval, unit: 'MILLISECONDS')
            }

            // If timeout is reached, throw an error
            error("❌ Timeout: App '${appName}' did not reach Synced and Healthy state within ${maxWaitTime} seconds.")
        }
    }
}

def hardRefreshApp(String appName) {
    println("🔄 Performing hard refresh for ArgoCD application: ${appName}")

    withCredentials([string(credentialsId: ArgoCD.ARGOCD_AUTH_TOKEN, variable: 'ARGOCD_AUTH_TOKEN')]) {
        withEnv([
            "ARGOCD_SERVER=${ArgoCD.ARGOCD_URL}",
            'ARGOCD_TOKEN=' + ARGOCD_AUTH_TOKEN, // For Groovy String interpolation fix
            "ARGOCD_USER=${ArgoCD.ARGOCD_USER}"
        ]) {
            try {
                // Execute hard refresh command
                def refreshCmd = "argocd app get ${appName} --hard-refresh --insecure --grpc-web"
                def result = sh(script: refreshCmd, returnStdout: true).trim()

                // Verify refresh was successful
                if (result.contains("Error")) {
                    error("❌ Failed to hard refresh application ${appName}: ${result}")
                }

                println("✅ Successfully initiated hard refresh for ${appName}")

                // Start sync process
                argocdSyncApp(appName)

            } catch (Exception e) {
                error("❌ Failed to hard refresh application ${appName}: ${e.message}")
            }
        }
    }
}