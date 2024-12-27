// FILE: dockerBuildAndPush.groovy
void dockerBuildAndPush(Map config) {
    // docker.withRegistry(config.dockerUrl, config.dockerRegistryCredentials) {
    //     dockerImage = docker.build(dockerImageName)
    //     dockerImage.push()
    // }
    
    // Check if the image exists in the registry
    boolean imageExists = dockerImageExists(config.dockerUrl, config.dockerRegistryCredentials, config.imageWithTag)

    // Clean temporary manifest folders
    sh "find -type d -name '*@tmp' -exec rm -rf {} +"

    // Always build and push the image if the job is running on the 'dev' branch
    if (imageExists && env.JOB_BRANCH != 'dev') {
        echo "Docker image '${config.imageWithTag}' already exists. Skipping build."
    } else {
        println("Building and pushing Docker image '${config.imageWithTag}'...")
        // Add timeout to this block to prevent hanging builds
        timeout(time: 10, unit: 'MINUTES') {
            docker.withRegistry(config.dockerUrl, config.dockerRegistryCredentials) {
                retry(3) {
                    def dockerImage = docker.build(config.imageWithTag)
                    dockerImage.push()
                    echo "Docker image '${config.imageWithTag}' built and pushed successfully."
                }
            }
        }
    }
}

// Helper function to check if a Docker image exists
boolean dockerImageExists(String dockerUrl, String credentialsId, String imageWithTag) {
    // Add retry functionality to handle intermittent Docker registry connection issues
    def maxRetries = 3
    def retryCount = 0
    def success = false

    while (!success && retryCount < maxRetries) {
        try {
            docker.withRegistry(dockerUrl, credentialsId) {
                def cmd = "docker manifest inspect ${imageWithTag} > /dev/null 2>&1"
                def result = sh(script: cmd, returnStatus: true)
                success = true
                return result == 0
            }
        } catch (Exception e) {
            retryCount++
            if (retryCount < maxRetries) {
                echo "Docker registry connection failed. Attempt ${retryCount}/${maxRetries}. Retrying in 10 seconds..."
                sleep(10)
            } else {
                error "Failed to connect to Docker registry after ${maxRetries} attempts: ${e.message}"
            }
        }
    }
}