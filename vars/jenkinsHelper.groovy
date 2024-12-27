// vars/jenkinsHelper.groovy


Map loadJenkinsConfig(body) {
    // sets the resolution strategy to delegate first, meaning that if a method or property is not found on the closure itself,
    // it will look for it on the delegate (the config map)
    body.resolveStrategy = Closure.DELEGATE_FIRST
    // sets the delegate of the closure to the config map
    body.delegate = config
    // executes the closure, allowing it to populate the config map with the provided configuration.
    body()
    Map jenkinsData = [:] // Init empty map that can be used to store key-values pairs later

    jenkinsHelper.parametersInit(repo: config.repo) // Call the parametersInit method from the jenkinsHelper script

    return jenkinsData
}

void parametersInit(Map config=[:]) {
    // Define the parameters
    // Always show the parameters
    def actions = [choice(
            choices: ['DEPLOY', 'RESTART', 'STOP', 'START'],
            description: 'Choose an action',
            name: 'ACTION')]
    def tags = [] // init empty array for tags
    def environments = [] // init empty array for environments
    def options = [] // init empty array for options

    // Skip build image option for testing
    options = [
        booleanParam(
            defaultValue: false,
            description: 'Skip build image for faster testing',
            name: 'SKIP_BUILD_IMAGE'),
        booleanParam(
            defaultValue: true,
            description: 'Skip image security scan for faster testing',
            name: 'SKIP_IMAGE_SCAN'),
        booleanParam(
            defaultValue: true,
            description: 'Skip code scanning for faster testing',
            name: 'SKIP_CODE_SCAN'),
        booleanParam(
            defaultValue: false,
            description: 'Skip secrets leak scanning for faster testing',
            name: 'SKIP_SECRETS_SCAN'),
    ]

    // If branch is dev, show only actions limited, not gonna show tags and environments.
    if (env.BRANCH_NAME != 'dev') {
        actions = [choice(
                choices: ['DEPLOY', 'RESTART', 'ROLLBACK', 'STOP', 'START'],
                description: 'Choose an action',
                name: 'ACTION')]

        // https://stackoverflow.com/questions/47562572/pipeline-get-tag-list
        tags = [
                gitParameter(
                    branch: '',
                    branchFilter: 'v.*', // This will filter only tags that start with 'v'
                    type: 'PT_TAG',
                    defaultValue: '',
                    description: 'Choose a tag from list',
                    name: 'TAG',
                    quickFilterEnabled: true,
                    requiredParameter: false,
                    selectedValue: 'NONE',
                    sortMode: 'DESCENDING_SMART',
                    tagFilter: '*',
                    useRepository: "${config.repo}"),
        ]
        // Deploy to multiple env, not single like any others demo.
        // environments = [
        //         choice(
        //             choices: ['STAGING', 'PRODUCTION'],
        //             description: 'Choose an environment to deploy',
        //             name: 'ENV')
        // ]
    }

    // After all, set the parameters
    properties([
        parameters(actions + tags + options)
    ])
}

void handleBuildDescription(Map config) {
    // Set the build description based on the parameters
    // Init currentBuildData with empty string
    def currentBuildData = ""

    // Check if the action parameter is set
    if (config.action) {
        currentBuildData = "Action: ${config.action}"
    }
    // Check if the tag parameter is set
    if (config.tag) {
        currentBuildData += "\n Tag: ${config.tag}"
    }
    // Check if the deployer parameter is set
    if (config.userName) {
        currentBuildData += "\n Deploy by: ${config.userName}"
    }

    // Check if ingress config is set
    if (config.ingressConfig) {
        currentBuildData += "\n Domain: ${config.ingressConfig.host}"
    }

    // Set the build description
    buildDescription(currentBuildData)
}