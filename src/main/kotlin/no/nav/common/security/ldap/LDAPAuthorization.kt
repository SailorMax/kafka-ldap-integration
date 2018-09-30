package no.nav.common.security.ldap

import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.Filter
import com.unboundid.ldap.sdk.SearchRequest
import com.unboundid.ldap.sdk.SearchScope
import com.unboundid.ldap.sdk.LDAPSearchException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * A class verifying group membership with LDAP compare-matched
 */

class LDAPAuthorization private constructor(
    private val uuid: String,
    val config: LDAPConfig.Config
) : LDAPBase(config) {

    // In authorization context, needs to bind the connection before compare-match between group and user
    // due to no anonymous access allowed for LDAP operations like search, compare, ...
    private val bindDN = config.toUserDN(JAASContext.username)
    private val bindPwd = JAASContext.password

    init {
        log.debug("Binding information for authorization fetched from JAAS config file [$bindDN]")

        try {
            ldapConnection.bind(bindDN, bindPwd)
            log.debug("Successfully bind to (${config.host},${config.port}) with $bindDN")
        } catch (e: LDAPException) {
            log.error("Authorization will fail! " +
                    "Exception during bind of $bindDN to (${config.host},${config.port}) - ${e.diagnosticMessage}")
        }
    }

    private fun getGroupDN(groupName: String): String =
            try {
                val filter = Filter.createEqualityFilter(config.grpUid, groupName)

                ldapConnection
                        .search(SearchRequest(config.grpBaseDN, SearchScope.SUB, filter, SearchRequest.NO_ATTRIBUTES))
                        .let {
                            if (it.entryCount == 1)
                                it.searchEntries[0].dn
                            else {
                                log.error("LDAP search couldn't resolve group DN for $groupName under ${config.grpBaseDN} ($uuid)")
                                ""
                            }
                        }
            } catch (e: LDAPSearchException) {
                log.error("Cannot resolve group DN for $groupName under ${config.grpBaseDN} ($uuid)")
                ""
            }

    private fun getGroupMembers(groupDN: String): List<String> =
            try {
                if (groupDN.isNotEmpty())
                    ldapConnection.getEntry(groupDN)
                            ?.getAttributeValues(config.grpAttrName)
                            ?.map { it.toLowerCase() } ?: emptyList()
                else
                    emptyList()
            } catch (e: LDAPException) {
                log.error("Cannot get group members - ${config.grpAttrName} - for $groupDN ($uuid)")
                emptyList()
            }

    override fun isUserMemberOfAny(user: String, groups: List<String>): Set<AuthorResult> =
            if (!ldapConnection.isConnected) {
                log.error("No LDAP connection, cannot verify $user membership in $groups ($uuid)")
                emptySet()
            } else {
                val userNodes = config.toUserDNNodes(user)

                groups.flatMap { groupName ->
                    val members = getGroupMembers(getGroupDN(groupName))
                    log.debug("Group membership, intersection of $members and $userNodes ($uuid)")
                    members.intersect(userNodes).map { AuthorResult(groupName, it) }
                }.let { result ->
                    log.debug("Intersection result - $result ($uuid)")
                    result.toSet()
                }
            }

    companion object {

        private val log: Logger = LoggerFactory.getLogger(LDAPAuthorization::class.java)

        fun init(uuid: String, configFile: String = ""): LDAPAuthorization = when (configFile.isEmpty()) {
            true -> LDAPAuthorization(uuid, LDAPConfig.getByClasspath())
            else -> LDAPAuthorization(uuid, LDAPConfig.getBySource(configFile))
        }
    }
}