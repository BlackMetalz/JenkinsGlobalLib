package org.jenkins.configs

class GitSCM implements Serializable {
    // Private gitlab credential
    static final String GIT_CREDENTIALS_ID = "jenkin_gitlab" // jenkin_gitlab (user for gitlab from jenkins)
    // Manifest repo
    static final String GITLAB_MANIFEST_REPO = "git@github.com:BlackMetalz/k8s-manifest.git"
    static final String GITLAB_MANIFEST_DIR = "k8s-manifest"
    static final String GITLAB_MANIFEST_BRANCH = "main"

    static final String PUBLIC_GITHUB_CREDENTIAL = "github" // BlackMetalz (kienlt public jenkins shared lib)
    static final String PUBLIC_GITLAB_CREDENTIAL = "gitlab_com" // blackmetalz (gitlab.com. Owner for all projects)

    // Init for retries value
    static final int retries = 0
    static final int maxRetries = 3
    // For Git user
    static final String gitUserName = "kienlt"
    static final String gitUserEmail = "kienlt@kienlt.com"
}