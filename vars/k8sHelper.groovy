// vars/k8sHelper.groovy
import groovy.json.JsonSlurper

def getK8sCluster(String k8sCluster) {
    def jsonContent = libraryResource('k8s_clusters.json') // Load the JSON file as a string
    def json = new JsonSlurper().parseText(jsonContent) // Parse the JSON content
    // println("Clusters: ${json.clusters}")

    if (json.clusters.containsKey(k8sCluster)) {
        return json.clusters[k8sCluster]
    } else {
        throw new Exception("Cluster '${k8sCluster}' not found in k8s_clusters.json")
    }
}

def rolloutRestartDeployment(Map config) {
    println("Restarting deployment ${config.deploymentName} in namespace ${config.namespace} in cluster ${config.k8sCluster}")
    // Restart the deployment
    withKubeConfig([credentialsId: "${config.k8sCluster}"]) {
        sh "kubectl rollout restart deployment/${config.deploymentName} -n ${config.namespace}"
    }
}

// Function to check Deployment xD
def checkDeploymentStatus(Map config) {
    // Validate required configurations
    if (!config.deploymentName) {
        error("Missing required parameter: 'deploymentName'.")
    }
    if (!config.namespace) {
        error("Missing required parameter: 'namespace'.")
    }

    def timeout = config.timeout ?: 600 // Default timeout is 10 minutes

    // Load the deployment check script
    def scriptContent = libraryResource('check-k8s-deployment')
    writeFile file: './check-k8s-deployment', text: scriptContent
    // Set execute permissions on the script
    sh "chmod +x ./check-k8s-deployment"

    // Use credentials and environment variables securely
    withCredentials([file(credentialsId: "${config.k8sCluster}", variable: 'KUBE_CONFIG')]) {
        // Ensure KUBECONFIG is securely passed to the shell environment
        def status = sh(
            script: "bash check-k8s-deployment -n ${config.namespace} -t ${timeout} ${config.deploymentName}",
            returnStatus: true,
            environment: [
                "KUBECONFIG=${env.KUBE_CONFIG}"
            ]
        )

        if (status != 0) {
            error("Deployment '${config.deploymentName}' in namespace '${config.namespace}' is not ready after ${timeout} seconds.")
        } else {
            echo "Deployment '${config.deploymentName}' in namespace '${config.namespace}' is ready."
        }
        
    }
}

def applyManifestFile(Map config) {
    // Validate required configurations
    if (!config.manifestFile) {
        error("Missing required parameter: 'manifestFile'.")
    }
    if (!config.k8sClusterName) {
        error("Missing required parameter: 'k8sClusterName'.")
    }
    // Load the manifest file content
    def manifestContent = readFile(config.manifestFile)

    // Write the manifest content to a temporary file
    def tempManifest = 'temp-manifest.yaml'
    writeFile file: tempManifest, text: manifestContent
    sh "cat ${tempManifest}"

    // Apply the manifest file using kubectl
    withKubeConfig([credentialsId: "${config.k8sClusterName}"]) {
        sh "kubectl apply -f ${tempManifest}"
    }
}