// vars/helmHelper.groovy


def bootstrapHelmChart(String branch, String jobHelmChartPath) {
    // sh "ls -lia"
    println("jobHelmChartPath: ${jobHelmChartPath}")
    
    // Check if the values.yaml file exists in the Helm chart directory
    def valuesFilePath = "${jobHelmChartPath}/values.yaml"
    def valuesFileExists = sh(script: "if [ -f ${valuesFilePath} ]; then echo 'true'; else echo 'false'; fi", returnStdout: true).trim()
    println("Checking if values.yaml exists: ${valuesFilePath}")
    println("Values file exists: ${valuesFileExists}")

    
    if (valuesFileExists == 'true') {
        println("Helm chart already initialized. Skipping bootstrap.")
    } else {
        println("Helm chart does not exist. Creating...")
        // sh "ls -lia ${jobHelmChartPath}"
        sh "helm create ${BASE_JOB_NAME}"
        // Rename values.yaml based on the branch
        def valuesFile = "${branch}.yaml"
        sh "cp ${jobHelmChartPath}/values.yaml ${jobHelmChartPath}/${valuesFile}"

        // Add namespace.yaml for ArgoCD
        // This help fucking auto create namespace in argocd works!
        println("Adding namespace.yaml to Helm chart templates.")
        def namespaceYaml = libraryResource('k8s/namespace.yaml')
        // Needed stripIndent() for remove wrong tab when generate and trim() for remove last empty line!
        writeFile file: "${jobHelmChartPath}/templates/namespace.yaml", text: namespaceYaml

        // Add fucking secret.yaml
        println("Adding secrets.yaml to Helm chart templates.")
        def secretYaml = libraryResource('k8s/secrets.yaml')
        writeFile file: "${jobHelmChartPath}/templates/secrets.yaml", text: secretYaml
        println("Bootstrap completed successfully.")
    }
}

def updateHelmValues(Map config) {
    println("Update Helm values for branch ${config.branch}")
    // println("Data to update: ${config}")

    // Check if the file exists; if not, create an empty file
    def fileExists = sh(script: "if [ -f ${config.branch}.yaml ]; then echo 'true'; else echo 'false'; fi", returnStdout: true).trim()
    if (fileExists == 'false') {
        println("${config.branch}.yaml file does not exist. Creating an empty file.")
        sh "touch ${config.branch}.yaml"
    }

    sh """
    set +x
    # Namespace define
    yq eval '.namespace = "${config.namespace}"' -i ${config.branch}.yaml
    # Service Define
    # yq eval '.service.type = "${config.serviceType}"' -i ${config.branch}.yaml
    yq eval '.service.type = "ClusterIP"' -i ${config.branch}.yaml
    yq eval '.service.port = ${config.servicePort}' -i ${config.branch}.yaml
    # Resource define
    yq eval '.replicaCount = "${config.replicaCount}"' -i ${config.branch}.yaml
    yq eval '.resources.requests.memory = "${config.memoryRequest}"' -i ${config.branch}.yaml
    yq eval '.resources.requests.cpu = "${config.cpuRequest}"' -i ${config.branch}.yaml
    yq eval '.resources.limits.memory = "${config.memoryLimit}"' -i ${config.branch}.yaml
    yq eval '.resources.limits.cpu = "${config.cpuLimit}"' -i ${config.branch}.yaml
    set -x
    """
    // if branch is dev, change pull policy to always since we always build image with dev tag!
    if (config.branch == 'dev') {
        sh """
        set +x
        yq eval '.image.pullPolicy = \"Always\"' -i ${config.branch}.yaml
        set -x
        """
    }

    // This is handle for stop/start deployment
    if (config.imageRepository || config.imageTag) {
        sh """
        # Repository define
        set +x
        yq eval '.image.repository = "${config.imageRepository}"' -i ${config.branch}.yaml
        yq eval '.image.tag = "${config.imageTag}"' -i ${config.branch}.yaml
        set -x
        """
    }

    // This is handle for ingress
    if (config.ingress && config.ingress != null) {
        sh """
        # Ingress define
        set +x
        yq eval '.ingress.enabled = true' -i ${config.branch}.yaml
        yq eval '.ingress.className = "nginx"' -i ${config.branch}.yaml
        yq eval '.ingress.hosts[0].host = "${config.ingress.host}"' -i ${config.branch}.yaml
        yq eval '.ingress.hosts[0].paths[0].path = "${config.ingress.path}"' -i ${config.branch}.yaml
        yq eval '.ingress.hosts[0].paths[0].pathType = "Prefix"' -i ${config.branch}.yaml
        set -x
        """
    }
    println("${config.secret}")
    // This is for handle fucking secret
    if (config.secret && config.secret != null) {
        sh """
        # Secret define
        set +x
        yq eval '.vault.secretPath = "${config.secretPath}/data/${config.jobName}/${config.jobEnv}"' -i ${config.branch}.yaml
        yq eval '.secrets."'"${config.secret}"'" = "<'"${config.secret}"'>"' -i "${config.branch}.yaml"
        #yq eval '.secrets."'"${config.secret}"'" = "'"${config.secret}"'"' -i "${config.branch}.yaml"

        # Add volumeMounts
        yq eval '.volumeMounts[0].name = "config-volume"' -i ${config.branch}.yaml
        yq eval '.volumeMounts[0].mountPath = "${config.secretLocation}/${config.secret}"' -i ${config.branch}.yaml
        yq eval '.volumeMounts[0].subPath = "'"${config.secret}"'"' -i ${config.branch}.yaml
        
        # Add volumes
        yq eval '.volumes[0].name = "config-volume"' -i ${config.branch}.yaml
        yq eval '.volumes[0].secret.secretName = "${config.jobName}-${config.jobEnv}-secrets"' -i ${config.branch}.yaml
        set -x
        """
    }
    
}

def updateReplicaCount(Map config) {
    println("Update Helm values for branch ${config.branch}")
    // println("Data to update: ${config}")

    // Check if the file exists; if not, break since file will exists already!
    def fileExists = sh(script: "if [ -f ${config.branch}.yaml ]; then echo 'true'; else echo 'false'; fi", returnStdout: true).trim()
    if (fileExists == 'false') {
        println("${config.branch}.yaml file does not exist. Exiting...")
        return
    } else {
        sh """
        # Resource define
        set +x
        yq eval '.replicaCount = "${config.replicaCount}"' -i ${config.branch}.yaml
        set -x
        """
    }

}