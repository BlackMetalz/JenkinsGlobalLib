package org.jenkins.configs

class SecretManager implements Serializable {
    static final String VAULT_URL = "http://vault.kienlt.local:8200"
    // com.cloudbees.plugins.credentials.CredentialsUnavailableException: Property 'vault-token' is currently unavailable
    static final String VAULT_TOKEN = "vaultToken"  // New crenedtials ID for vault token with kind `Vault Token Credential`

}