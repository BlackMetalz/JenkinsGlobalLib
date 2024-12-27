# JenkinsGlobalLib

- What will look like when push images to dockerhub? it will auto create the repo if it is not exists
![Docker Image](images/docker1.png)

### Required plugin for this shared library
- HTTP Request Plugin
- Git
- Docker Pipeline / Docker Commons
- Build User Vars
- Active Choices
- Git Parameter Plug-In
- Kubernetes CLI Plugin
- LDAP Plugin
- List Git Branches Parameter PlugIn
- Build Timeout
- HashiCorp Vault
- Workspace Cleanup
- Git Parameter ([for show tags](https://plugins.jenkins.io/git-parameter/))
- Build Name and Description Setter
- Sonarqube / Quality Gates Plugin / Sonar Gerrit Plugin / SonarQube Generic Coverage Plugin / SonarQube Scanner for Jenkins /Sonar Quality Gates Plugin
- Pipeline Utility Steps (if not `No such DSL method 'readJSON' found among steps` )

### Required softwares/binary for this shared library
- `trivy`: `/usr/local/bin/trivy --version` ==> Version: 0.51.1 (maybe can higher since last time i download was more than 6 months ago.)
- `sonarqube`: install sonarqube and use plugin to scan


### Something note for Jenkins user in agent node
- jenkins user in agent need to have permission to access the repo of job in order to list tag, which is used for parameter.

### Argocd Vault Plugin
There are 3 fucking types
- argocd-vault-plugin-kustomize
- argocd-vault-plugin
- argocd-vault-plugin-helm

### Require for gitleaks
Installation:
```
# Download
curl -s https://api.github.com/repos/gitleaks/gitleaks/releases/latest | grep "browser_download_url.*linux_x64" | cut -d '"' -f 4 | wget -qi -
# Decompress
tar -xzf gitleaks_*_linux_x64.tar.gz
# Chmod
chmod +x gitleaks-linux-x64
# Move to path1
sudo mv gitleaks-linux-x64 /usr/local/bin/gitleaks
```