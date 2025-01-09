// vars/sonarqubeHelper.groovy

// Powered by ChatGPT 3.5
def createSonarqubeProperties(Map params) {
    // Check if file exists
    if (fileExists('sonar-project.properties')) {
        echo "sonar-project.properties already exists"
        return
    } else {
        // Load the SonarQube properties template
        def sonarPropertiesTemplate = libraryResource('sonarqube/default-sonar-project.properties')

        // Replace placeholders with actual values
        def sonarProperties = sonarPropertiesTemplate
            .replace('${params.projectName}', params.projectName)
            .replace('${params.branchName}', params.branchName)
            .replace('${dateTime}', dateTime)

        // Write the properties to a file
        writeFile file: 'sonar-project.properties', text: sonarProperties
    }
    
    /*
    writeFile file: 'sonar-project.properties', text: 
        """
        sonar.projectKey=${params.projectName}_${params.branchName}
        sonar.projectName=${params.projectName}_${params.branchName}
        sonar.projectVersion=1.0
        sonar.sourceEncoding=UTF-8
        sonar.java.binaries=.
        """
    */
}

def executeSonarqubeAnalysis(Map params) {
    def scannerHome = tool 'SonarScanner'
    // Run the scanner
    withSonarQubeEnv('sonar-api') {
        sh """
            set +x
            # Set and verify Java environment
            export JAVA_HOME=/usr
            echo "JAVA_HOME set to: \${JAVA_HOME}"
            echo "Java version:"
            java -version
            echo "Java location:"
            which java
            
            # Run scanner with debug (-X)
            ${scannerHome}/bin/sonar-scanner \
                -Dsonar.java.home=\${JAVA_HOME}
            set -x
        """
    }

    // Check the quality gate
    timeout(time: 15, unit: 'MINUTES') {
        def qg = waitForQualityGate()
        if (qg.status != 'OK') {
            error "Pipeline aborted due to quality gate failure: ${qg.status}"
        }
    }
}
