package com.instaclustr.kafka.ldap.authentication

import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.ResultCode
import com.instaclustr.kafka.ldap.Monitoring
import com.instaclustr.kafka.ldap.common.LDAPBase
import com.instaclustr.kafka.ldap.LDAPConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * A class verifying username and password through simple LDAP bind
 */

class LDAPAuthentication private constructor(val config: LDAPConfig.Config) : LDAPBase(config) {

    private fun bindOk(uDN: String, pwd: String): AuthenResult =
            try {
                if (ldapConnection.bind(uDN, pwd).resultCode == ResultCode.SUCCESS)
                    AuthenResult(true, uDN, "")
                else {
                    AuthenResult(false, uDN, "LDAP bind unsuccessful for $uDN - unknown situation :-(")
                }
            } catch (e: LDAPException) {
                AuthenResult(false, uDN, "LDAP bind exception for $uDN - ${e.diagnosticMessage}")
            }

    override fun canUserAuthenticate(userDNs: List<String>, pwd: String): Set<AuthenResult> =
        if (!ldapConnection.isConnected) {
            emptySet()
        } else {
            userDNs
                    .map { uDN -> bindOk(uDN, pwd) }
                    .also { result -> if (result.all { !it.authenticated }) result.forEach { log.error(it.errMsg) } }
                    .filter { it.authenticated }
                    .toSet()
        }

    companion object {

        private val log: Logger = LoggerFactory.getLogger(LDAPAuthentication::class.java)

        fun init(configFile: String = ""): LDAPAuthentication = when (configFile.isEmpty()) {
            true -> LDAPAuthentication(LDAPConfig.getByClasspath())
            else -> LDAPAuthentication(LDAPConfig.getBySource(configFile));
        }
    }
}