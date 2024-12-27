package org.jenkins.configs

class ArgoCD implements Serializable {
    static final String ARGOCD_URL = "argocd_url_here"
    static final String ARGOCD_USER = "admin"
    static final String ARGOCD_AUTH_TOKEN = "ARGOCD_AUTH_TOKEN" // Jenkins secret text credential ID
    static final String ARGOCD_CLUSTER_CONTEXT = "kienlt-rke1-development" // Jenkins secret text credential ID where the ArgoCD app is deployed
}