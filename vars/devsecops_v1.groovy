import org.jenkins.configs.GeneralConfig
import org.jenkins.configs.Docker
import org.jenkins.configs.GitSCM
import org.jenkins.configs.ArgoCD
import groovy.json.JsonSlurper
// vars/helmPipeline.groovy
// Jenkins project name = helm chart name and argocd app name
// Image will push to registry of dockerhub

// Why we need 'Closure body': 
// - We want to pass/define/use the configuration to the Jenkinsfile
void call(Closure body) {
    // Define the default configuration
    def config = [:] // initializes an empty map to hold the configuration.
    // sets the resolution strategy to delegate first, meaning that if a method or property is not found on the closure itself,
    // it will look for it on the delegate (the config map)
    body.resolveStrategy = Closure.DELEGATE_FIRST 
    // sets the delegate of the closure to the config map
    body.delegate = config
    // executes the closure, allowing it to populate the config map with the provided configuration.
    body()

    jenkinsHelper.parametersInit(repo: config.repo) // Call the parametersInit method from the jenkinsHelper script
    tag = params.TAG ?: 'dev' // Get the tag from the parameters, if not set, default to 'dev'
    // Build and push the Docker image                          
    imageWithTag = "${Docker.PUBLIC_DOCKER_REGISTRY_USER}/${JOB_NAME.split('/')[0]}:${tag}".toLowerCase() // Image name


    // Getting some fucking global config here to use laters in steps
    DEPLOY_ENV = (JOB_NAME.split('/')[1] == 'main') ? 'production' : 'development'
    resourceConfig = config.resources[DEPLOY_ENV]  // Treat this as fucking Map, not String!
    if (config.ingress) {
        ingressConfig = config.ingress[DEPLOY_ENV]
    }

    // For some variable. I think i need to create a class for this
    k8sClusterUrl = k8sHelper.getK8sCluster(resourceConfig.k8sCluster)
    argocdClusterConext = ArgoCD.ARGOCD_CLUSTER_CONTEXT

    // println(config) // This will print the configuration of Jenkinsfile to the console
    // Ex: {repo=git@gitlab.com:kienlt-cicd/python-api-app.git, branch=main,dev}

    // env.WORKSPACE is the default workspace of Jenkins. 
    // Value example: /data/jenkins/workspace/python-api-app_dev (with _dev is the branch name)
    pipeline {
        agent { label GeneralConfig.DOCKERFILE_BUILD_AGENT } // Pick the docker agent to build from GeneralConfig

        options {
            disableConcurrentBuilds() // Disable concurrent builds, avoid conflict
            buildDiscarder(logRotator( // Discard old builds, avoid full inodes when build number are too many and build way too oftens!
                    numToKeepStr: '10',
                    artifactNumToKeepStr: '2'
            ))
        }

        // environment should contain only key and value. Nothing more!
        environment {
            // JOB_NAME = "python-api-app/dev"
            BASE_JOB_NAME = "${JOB_NAME.split('/')[0]}".toLowerCase() // Extract the base job name
            JOB_BRANCH = "${JOB_NAME.split('/')[1]}".toLowerCase() // Extract the branch name from the job name
            // Set this in env to call to any function in the pipeline
            DEPLOY_ENV = "${DEPLOY_ENV}"
            // JOB_HELM_CHART_PATH = /data/jenkins/workspace/python-api-app_dev/k8s-manifest/python-api-app
            ARGOCD_APP_NAME = argocdHelper.argocdAppName(BASE_JOB_NAME, JOB_BRANCH) // Define the ArgoCD app name
            JOB_HELM_CHART_PATH = "${env.WORKSPACE}/${GitSCM.GITLAB_MANIFEST_DIR}/${BASE_JOB_NAME}"
            MANIFEST_PATH = "${env.WORKSPACE}/${GitSCM.GITLAB_MANIFEST_DIR}" // Manifest Directory
            
            // Images name / repo
            dockerRegistryCredentials = "${Docker.PUBLIC_DOCKER_REGISTRY_CRE}" // Docker registry credentials
            dockerImageName = "${Docker.PUBLIC_DOCKER_REGISTRY_USER}/${BASE_JOB_NAME}:${env.JOB_BRANCH}" // Image name
            dockerRepoName = "${env.dockerImageName.split(':')[0]}" // Extract the repo name

            // Docker custom config
            DOCKER_CLI_HINTS = 'false'
            DOCKER_BUILDKIT = '1'

            // Golang
            GOPATH = "${WORKSPACE}/gopath"
            PATH = "${GOPATH}/bin:/usr/local/go/bin:${env.PATH}"
        }

        stages {
            stage('Validate Tag') {
                when {
                    expression {
                        return params.ACTION == 'DEPLOY' || params.ACTION == 'ROLLBACK' // Only run when action = DEPLOY or ROLLBACK
                    }
                }

                steps {
                    script {
                        // Immediately return with success if this is the first build
                        // Lets Jenkin clone the shared library for the first time
                        if (currentBuild.number == 1) {
                            currentBuild.result = 'SUCCESS'
                            return
                        }
                        // Dynamically abort if no tag is selected
                        if (!tag || tag.trim() == '') {
                            error("Build aborted: No tag selected.")
                        }

                        // Lets user know that build started
                        // msg = """ENV: <b>${env.DEPLOY_ENV}</b> JOB: <b>${env.JOB_NAME}</b> - Build #<a href="${env.BUILD_URL}">${env.BUILD_NUMBER}</a> Started"""
                        sendNotifyTelegram(buildType: "start")
                    }
                }
            }

            stage('Sonarqube - SAST') {
                when {
                    expression {
                        return params.ACTION == 'DEPLOY' // Only run when action = DEPLOY
                    }
                    expression { 
                        return !params.SKIP_CODE_SCAN  // Skip build image if set to true
                    }
                }

                steps {
                    script {
                        sonarqubeHelper.createSonarqubeProperties(
                            projectName: BASE_JOB_NAME,
                            branchName: JOB_BRANCH
                        )

                        sonarqubeHelper.executeSonarqubeAnalysis()
                    }
                }
            }

            stage('Secrets Leak Scan') {
                when {
                    expression {
                        return params.ACTION == 'DEPLOY' // Only run when action = DEPLOY
                    }
                    expression { 
                        return !params.SKIP_SECRETS_SCAN  // Skip build image if set to true
                    }
                }

                steps {
                    script {
                        // Scan for secrets leak
                        gitleaksHelper.scanSecretsLeak(
                            reportFormat: 'json',
                            reportPath: 'gitleaks_report.json',
                            verbose: true,
                            failOnError: true,
                            maxTargetMB: 5,
                            followSymlinks: false
                        )
                    }
                }
            }

            stage('Dependency vulnerabilities and OPA config test') {
                when {
                    expression {
                        return params.ACTION == 'DEPLOY'
                    }
                }
                stages {
                    stage('Security Scans') {
                        parallel {
                            stage('Dependency-Check') {
                                steps {
                                    script {
                                        // Install govulncheck if not already installed
                                        sh '''
                                            which govulncheck || go install golang.org/x/vuln/cmd/govulncheck@latest
                                        '''
                                        
                                        // Run vulnerability check
                                        sh '''
                                            export TMPDIR=${WORKSPACE}/tmp-go
                                            mkdir -p ${TMPDIR}
                                            govulncheck ./...
                                        '''          
                                    }
                                }
                            }

                            stage('OPA Conftest') {
                                steps {
                                    script {
                                        println("Scanning image '${imageWithTag}' for OPA conftest...")
                                        sh '''
                                            docker run --rm -v $(pwd):/project kienlt992/opa-custom:v0.56.0 Dockerfile
                                        '''
                                    }
                                }
                            }
                        }
                    }
                }
            }

            stage('Docker build and push') {
                when {
                    expression {
                        return params.ACTION == 'DEPLOY' // Only build and push when action is DEPLOY
                    }
                    expression { 
                        return !params.SKIP_BUILD_IMAGE  // Skip build image if set to true
                    }
                }

                steps {
                    script {
                        dockerHelper.dockerBuildAndPush(
                            dockerUrl: Docker.PUBLIC_DOCKER_URL,
                            dockerRegistryCredentials: dockerRegistryCredentials,
                            imageWithTag: imageWithTag
                        )
                    }
                }
            }

            // Trivy scan after build and push
            stage('Vulnerability Image Scan - Trivy') {
                when {
                    expression {
                        return params.ACTION == 'DEPLOY' // Only run when action = DEPLOY
                    }
                    expression { 
                        return !params.SKIP_IMAGE_SCAN  // Skip build image if set to true
                    }
                }

                steps {
                    script {
                        // Trivy scan after build and push
                        trivyHelper.scanImage(
                            imageName: imageWithTag
                        )
                    }
                }
            }

            // Move after Docker stage since this will include into the image
            stage('Checkout SCM - Manifest') {
                steps {
                    script {
                        sh "env|sort"
                        // println(GitSCM.GITLAB_MANIFEST_REPO)
                        // Checkout manifest repo to a folder for clean later
                        dir("${MANIFEST_PATH}") {
                            // Checkout the manifest repo, with default branch is main
                            gitHelper.gitCheckoutSCM(GitSCM.GITLAB_MANIFEST_REPO, GitSCM.GITLAB_MANIFEST_BRANCH)
                        }

                        // Build Description
                        def cause = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')
                        if (cause.userName) {
                            userName = cause.userName
                        } else {
                            userName = "SCM Trigger"
                        }
                        // echo "userName start build: ${cause.userName}"
                        jenkinsHelper.handleBuildDescription(
                            action: params.ACTION,
                            tag: tag,
                            userName: userName,
                            ingressConfig: ingressConfig ? ingressConfig : null // Pass ingressConfig if available
                        )
                    }
                }
            }

            stage('ROLLBACK Deployment') {
                when {
                    expression {
                        return params.ACTION == 'ROLLBACK' // Only run when action = ROLLBACK
                    }
                }

                steps {
                    script {
                        // Check if the app exists in ArgoCD
                        def appExists = argocdHelper.checkAppExists(ARGOCD_APP_NAME)
                        if (!appExists) {
                            error("App '${ARGOCD_APP_NAME}' does not exist in ArgoCD. Cannot rollback.")
                        }
                        // Check image exists in registry
                        boolean imageExists = dockerHelper.dockerImageExists(Docker.PUBLIC_DOCKER_URL, Docker.PUBLIC_DOCKER_REGISTRY_CRE, imageWithTag)
                        if (!imageExists) {
                            error("Image '${imageWithTag}' does not exist in the registry. Cannot rollback.")
                        }
                    }
                }
            }

            stage('Bootstrap Helm Chart') {
                when {
                    expression {
                        return params.ACTION == 'DEPLOY' // Only build and push when action is DEPLOY
                    }
                }

                steps {
                    script {
                        dir("${MANIFEST_PATH}") {
                            helmHelper.bootstrapHelmChart(JOB_BRANCH, JOB_HELM_CHART_PATH)
                        }
                    }
                }
            }

            stage('Update Helm Chart values') {
                when {
                    expression {
                        return params.ACTION == 'DEPLOY' || params.ACTION == 'ROLLBACK' // Only build and push when action is DEPLOY
                    }
                }

                steps {
                    script {
                        // Dynamically set ENV based on JOB_BRANCH
                        // DEPLOY_ENV = (JOB_BRANCH == 'main') ? 'production' : 'development'
                        echo "Environment is set to: ${DEPLOY_ENV}"

                        // Update Helm values
                        dir("${MANIFEST_PATH}/${BASE_JOB_NAME}") {
                            helmHelper.updateHelmValues(
                                jobName: "${BASE_JOB_NAME}",
                                jobEnv: "${DEPLOY_ENV}",
                                namespace: "${config.namespace}",
                                branch: "${JOB_BRANCH}",
                                imageRepository: "${env.dockerRepoName}",
                                imageTag: "${tag}",
                                serviceType: "${config.serviceType}",
                                servicePort: "${config.servicePort}",
                                replicaCount: "${resourceConfig.replicaCount}",
                                memoryRequest: "${resourceConfig.memoryRequest}",
                                cpuRequest: "${resourceConfig.cpuRequest}",
                                memoryLimit: "${resourceConfig.memoryLimit}",
                                cpuLimit: "${resourceConfig.cpuLimit}",
                                ingress : ingressConfig ?: null, // Pass ingressConfig if available
                                // secretName: config.secret ?: null,  // Groovy Elvis operator
                                // secretPath: config.secretPath ?: null // Groovy Elvis operator
                                secret: config.secret ?: null,
                                secretPath: config.secretPath ?: null,
                                secretLocation: config.secretLocation ?: null
                            )
                        }

                        // Lock for avoid fucking conflict!
                        lock('manifest-repo-lock') {
                            gitHelper.UpdateManifest(
                                manifestPath: "${MANIFEST_PATH}",
                                branch: "${GitSCM.GITLAB_MANIFEST_BRANCH}",
                                userName: "${GitSCM.gitUserName}",
                                userEmail: "${GitSCM.gitUserEmail}",
                                baseJobName: "${BASE_JOB_NAME}",
                                credentialsId: GitSCM.PUBLIC_GITLAB_CREDENTIAL
                            )
                        }
                    }
                }
            }

            stage('Vulnerability Scan - Kubernetes') {
                steps {
                    script {
                        // Kubesec Scan
                        dir("${MANIFEST_PATH}/${BASE_JOB_NAME}") {
                            sh '''
                                echo "Running Kubesec security scan..."
                                set +x
                                SCAN_RESULT=$(helm template . -f ${JOB_BRANCH}.yaml | curl --max-time 30 -sSX POST --data-binary @- https://v2.kubesec.io/scan)
                                
                                # Check the scores using jq
                                LOW_SCORE_FOUND=$(echo "$SCAN_RESULT" | jq '[.[] | select(.score < 0)] | length')
                                set -x
                                # Exit with status 1 if a score lower than 0 is found, otherwise exit with status 0
                                if [ "$LOW_SCORE_FOUND" -gt 0 ]; then
                                    echo "Kubesec security scan failed. Found $LOW_SCORE_FOUND vulnerabilities with a score lower than 0."
                                    exit 1
                                else
                                    echo "Kubesec security scan passed. No vulnerabilities found with a score lower than 0."
                                    exit 0
                                fi

                            '''
                        }
                    }
                }
   
            }

            stage('Restart deployment') {
                when { 
                    expression {
                        return params.ACTION == 'RESTART' // run when action is RESTART
                    }
                }

                steps {
                    script {
                        // Hard refresh for sync data from AVP if it has
                        argocdHelper.hardRefreshApp(ARGOCD_APP_NAME)
                        
                        // Restart the deployment
                        k8sHelper.rolloutRestartDeployment(
                            k8sCluster: resourceConfig.k8sCluster,
                            deploymentName: ARGOCD_APP_NAME,
                            namespace: config.namespace
                        )

                        // Create the health checker. Yes, we need this!
                        argocdHelper.argocdHealthChecker(ARGOCD_APP_NAME)
                    }
                }
            }

            stage('Stop/Start deployment') {
                when { 
                    expression {
                        return params.ACTION == 'STOP' || params.ACTION == 'START' // run when action is STOP
                    }
                }

                steps {
                    script {
                        // Determine the replica count based on the action
                        def replicaCount = (params.ACTION == 'STOP') ? 0 : resourceConfig.replicaCount

                        // Stop the deployment
                        // Update Helm values
                        dir("${MANIFEST_PATH}/${BASE_JOB_NAME}") {
                            helmHelper.updateReplicaCount(
                                branch: "${JOB_BRANCH}",
                                replicaCount: "${replicaCount}"
                            )
                        }

                        // Lock for avoid fucking conflict!
                        lock('manifest-repo-lock') {
                            gitHelper.UpdateManifest(
                                manifestPath: "${MANIFEST_PATH}",
                                branch: "${GitSCM.GITLAB_MANIFEST_BRANCH}",
                                userName: "${GitSCM.gitUserName}",
                                userEmail: "${GitSCM.gitUserEmail}",
                                baseJobName: "${BASE_JOB_NAME}",
                                credentialsId: GitSCM.PUBLIC_GITLAB_CREDENTIAL
                            )
                        }

                        // sync to argocd
                        argocdHelper.argocdHandler(
                            manifestTemplate: 'helm', // Required for helm chart deployment!
                            appName: ARGOCD_APP_NAME, // Define the app name
                            GITLAB_MANIFEST_REPO: GitSCM.GITLAB_MANIFEST_REPO,
                            GITLAB_MANIFEST_BRANCH: GitSCM.GITLAB_MANIFEST_BRANCH,
                            appPath: BASE_JOB_NAME,
                            project: argocdHelper.mapBranchToEnvironment(JOB_BRANCH), // Define the project
                            argocdClusterConext: argocdClusterConext,
                            k8sClusterUrl: k8sClusterUrl,
                            namespace: config.namespace,
                            // Define the values file, needed for helm
                            valuesFile: "${JOB_BRANCH}.yaml",
                            secret: config.secret ?: null
                        )
                    }
                }
            }
            

            stage('Deploy to K8S') {
                when {
                    expression {
                        return params.ACTION == 'DEPLOY' || params.ACTION == 'ROLLBACK' // Only build and push when action is DEPLOY or ROLLBACK
                    }
                }

                steps {
                    script {
                        // Get k8s cluster URL
                        // println(resourceConfig.k8sCluster)
                        // Check if app exists in ArgoCD with flag
                        def appExists = argocdHelper.checkAppExists(ARGOCD_APP_NAME)
                        println("App exists: ${appExists}")

                        println("Deploying to cluster ${resourceConfig.k8sCluster}")
                        
                        // Deploy to K8S using ArgoCD handler
                        argocdHelper.argocdHandler(
                            manifestTemplate: 'helm', // Required for helm chart deployment!
                            appName: ARGOCD_APP_NAME, // Define the app name
                            GITLAB_MANIFEST_REPO: GitSCM.GITLAB_MANIFEST_REPO,
                            GITLAB_MANIFEST_BRANCH: GitSCM.GITLAB_MANIFEST_BRANCH,
                            appPath: BASE_JOB_NAME,
                            project: argocdHelper.mapBranchToEnvironment(JOB_BRANCH), // Define the project
                            k8sClusterName: resourceConfig.k8sCluster,
                            argocdClusterConext: argocdClusterConext,
                            k8sClusterUrl: k8sClusterUrl,
                            namespace: config.namespace,
                            // Define the values file, needed for helm
                            valuesFile: "${JOB_BRANCH}.yaml",
                            secret: config.secret ?: null
                        )

                        // Check if branch is dev and app flag exists, then restart the deployment
                        if (JOB_BRANCH == 'dev' && appExists ) {
                            k8sHelper.rolloutRestartDeployment(
                                k8sCluster: resourceConfig.k8sCluster,
                                deploymentName: ARGOCD_APP_NAME,
                                namespace: config.namespace
                            )
                        }

                        // Create the health checker
                        argocdHelper.argocdHealthChecker(ARGOCD_APP_NAME)

                        // // Check Deployment status
                        // k8sHelper.checkDeploymentStatus(
                        //     k8sCluster: resourceConfig.k8sCluster,
                        //     namespace: config.namespace,
                        //     deploymentName: ARGOCD_APP_NAME
                        // )

                    }
                }
            }

            stage('Security Scan with OWASP ZAP') {
                steps {
                    // User root for fix permission issues
                    sh '''
                    docker run --rm \
                        --user 0 \
                        -v $(pwd):/zap/wrk \
                        zaproxy/zap-stable zap-full-scan.py \
                        -t http://devsecops-golang.rke2-cluster.kienlt.local \
                        -r zap_report.html\
                        -I  # Ignore warnings (WARN-NEW) and only fail on critical issues
                    '''
                }
            }

        }

        // Post needs to be outside of the stages and inside the pipeline
    
        post {
            always {
                script {
                    // Clean only the manifest repo directory
                    dir("${env.MANIFEST_PATH}") {
                        echo "Before Clean only the manifest repo directory"
                        // sh "ls -lia"
                        deleteDir()
                    }

                    // Send notification to Telegram to lets user know that build finished and the result
                    sendNotifyTelegram()

                    // Show user info about k8s info if action is deploy
                    if (params.ACTION == 'DEPLOY') {
                        sendNotifyTelegram.sendDeploymentInfo(resourceConfig, ingressConfig)
                    }

                    sh '''
                        # Ensure write permissions
                        if [ -d "${WORKSPACE}/tmp-go" ]; then
                            chmod -R 755 ${WORKSPACE}/tmp-go
                            rm -rf ${WORKSPACE}/tmp-go
                        fi
                        
                        if [ -d "${GOPATH}" ]; then
                            chmod -R 755 ${GOPATH}
                            rm -rf ${GOPATH}
                        fi
                    '''

                    publishHTML (target: [
                        allowMissing: false,
                        alwaysLinkToLastBuild: true,
                        keepAll: true,
                        reportDir: '.',
                        reportFiles: 'zap_report.html',
                        reportName: 'OWASP ZAP Security Report'
                    ])
                    
                    // Remove the ZAP report after publishing
                    sh 'rm -f zap_report.html'

                }
            }
        }
    }
}
