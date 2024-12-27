// vars/trivyHelper.groovy


def scanImage(Map config) {
    def imageName = config.imageName ?: ''

    // Check if image name is empty
    if (!imageName) {
        error "Image name is required for Trivy scan"
    }

    println "Starting Trivy security scan for image: ${imageName}"

    try {
        // Run Trivy with default configuration
        def scanResult = sh(
            script: """
                trivy image \
                --exit-code 1 \
                --severity HIGH,CRITICAL \
                --no-progress \
                --format table \
                ${imageName}
            """,
            returnStatus: true
        )

        if (scanResult == 0) {
            println "Security scan passed for ${imageName}"
        } else {
            error "Trivy scan detected vulnerabilities in ${imageName}"
        }
    } catch (Exception e) {
        error "Failed to scan image: ${e.getMessage()}"
    }
}