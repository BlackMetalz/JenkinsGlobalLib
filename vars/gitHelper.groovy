// /vars/gitHelper.groovy

import org.jenkins.configs.GitSCM
// vars/gitUpdateManifest.groovy

void UpdateManifest(Map params) {
    // println(params) // This will print the configuration of Jenkinsfile to the console
    String manifestPath = params.manifestPath
    String branch = params.branch
    String userName = params.userName
    String userEmail = params.userEmail
    String baseJobName = params.baseJobName
    String credentialsId = params.credentialsId

    maxRetries = GitSCM.maxRetries

    // Update the manifest repo
    dir(manifestPath) {
        withCredentials([sshUserPrivateKey(credentialsId: credentialsId, keyFileVariable: 'GITLAB_SSH_KEY')]) {
            sh """
            eval \$(ssh-agent -s)
            ssh-add \$GITLAB_SSH_KEY
            git checkout ${branch}
            git config user.name "${userName}"
            git config user.email "${userEmail}"
            git status
            git add -A
            git pull origin ${branch}
            if ! git diff-index --quiet HEAD; then
                git commit -m "Update manifest for job ${baseJobName} in branch ${branch}"
                for i in {1..${maxRetries}}; do
                    if git push origin ${branch}; then
                        echo "Push succeeded"
                        break
                    else
                        echo "Push failed, retrying in 5 seconds..."
                        sleep 5
                    fi
                done
            else
                echo "No changes to commit"
            fi

            """
        }
    }
}

def fetchTags() {
    // Fetch tags from Git repository
    def tags = []
    try {
        // Use shell command to get the last 15 tags in descending order
        def result = sh(script: "git fetch --tags --force && git tag --sort=-creatordate | head -n 15", returnStdout: true).trim()
        tags = result.tokenize("\n")
    } catch (Exception e) {
        echo "Error fetching tags: ${e.getMessage()}"
    }

    // Default fallback in case of error
    if (tags.isEmpty()) {
        tags = ['No tags found']
    }

    return tags
}


import org.jenkins.configs.GitSCM

// vars/gitCheckoutSCM.groovy
void gitCheckoutSCM(String repo, String branch) {
    retries = GitSCM.retries
    maxRetries = GitSCM.maxRetries
    // Some logic to retry the checkout, if it fails.
    while (retries < maxRetries) {
        try {
            checkout([$class: 'GitSCM', 
                branches: [[name: "${branch}"]],
                doGenerateSubmoduleConfigurations: false,
                extensions: [],
                gitTool: 'Default',
                userRemoteConfigs: [[credentialsId: GitSCM.PUBLIC_GITLAB_CREDENTIAL ,url: "${repo}"]]
                ])
            break
        } catch (Exception e) {
            retries++
            echo "Checkout failed. Attempt ${retries} of ${maxRetries}"
            if (retries == maxRetries) {
                echo "Max retries for gitCheckoutSCM reached. Throwing exception."
                throw e
            }
        }
    }
}