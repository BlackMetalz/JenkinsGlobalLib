package org.jenkins.configs

class Docker implements Serializable {

    static final String PRIVATE_DOCKER_REGISTRY_CRE = "jenkin_harbor" // Credentials(username with password) demo/****** (jenkin harbor user https://registry.kienlt.local/)
    static final String PRIVATE_DOCKER_REGISTRY_REPO = "https://registry.kienlt.local"

    static final String PUBLIC_DOCKER_REGISTRY_USER = "dockerhub_username" // repo in docker hub
    static final String PUBLIC_DOCKER_REGISTRY_CRE = "dockerhub" // Credentials(username with password) dockerhub_username/****** (for pull img from docker hub)
    static final String PUBLIC_DOCKER_URL = "https://index.docker.io/v1/"
}