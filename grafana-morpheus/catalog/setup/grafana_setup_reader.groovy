import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import java.security.cert.X509Certificate
import java.security.SecureRandom

// =============================================================================
// grafana_setup_reader.groovy  (v1.1.1 - keep existing roles)
// v1.1.1: adding the role to an existing user keeps the roles it already has
//         (PUT /api/users replaces the whole role list).
// v1.1.0: first task of the "Grafana - Connect Morpheus" workflow (the separate
//         "Grafana - Setup Reader Access" item is gone). Username and an
//         optional password come from the form. An existing user is not
//         created again; its password changes only when one is typed.
// v1.0.0: first version (own catalog item, fixed user grafana-reader)
//
// Runs on every "Grafana - Connect Morpheus" order, before the connect task.
// Creates or repairs, idempotently:
//   1. role "Grafana Reader" - read only: activity, apps, clusters, clouds,
//      hosts/VMs, appliance health, monitoring, guidance, storage (datastores);
//      all groups and clouds
//   2. OAuth client "grafana" - access token validity from the form (days)
//   3. the reader user with that role:
//        missing                -> created; password from the form, or a
//                                  random one when the field is empty
//        present, password typed -> password set to the typed one
//        present, no password    -> kept as is (password already in Cypher)
//      The password is kept in Cypher secret/<user>-password (never printed).
//      If the user exists and neither the form nor Cypher has its password,
//      the task stops and asks for one.
//   4. a login test with the OAuth client
//
// Auth: the executing user's own token (morpheus.apiAccessToken); the caller
// needs Roles, Users, Clients (Settings) and Cypher rights - a master-tenant
// System Admin.
//
// Inputs (Form Inputs, customOptions):
//   readerUsername : read-only API user (Text, default grafana-reader)
//   readerPassword : its password (Password, optional; empty = random for a new
//                    user, unchanged for an existing one)
//   tokenDays      : token lifetime in days (Text, default 365)
// =============================================================================

final String ROLE_NAME   = "Grafana Reader"
final String CLIENT_ID   = "grafana"
final List READ_PERMS    = ["activity", "apps", "infrastructure-cluster", "admin-zones", "admin-servers",
                            "admin-health", "monitoring", "guidance", "infrastructure-storage"]

def opts = [
    { customOptions },
    { morpheus.customOptions },
    { morpheus['customOptions'] },
].findResult { tryIt ->
    try { def v = tryIt(); (v instanceof Map && !v.isEmpty()) ? v : null } catch (Throwable t) { null }
} ?: [:]

def applianceUrl = morpheus.applianceUrl?.toString()?.replaceAll('/+$', '')
def bearerToken  = morpheus.apiAccessToken?.toString()
if (!applianceUrl || !bearerToken) {
    throw new RuntimeException("Appliance URL or the executing user's API token is not available to this task.")
}
def daysText = (opts.tokenDays ?: "365").toString().trim()
if (!daysText.isInteger() || daysText.toInteger() < 1 || daysText.toInteger() > 3650) {
    throw new RuntimeException("Token lifetime must be 1-3650 days (got '${daysText}').")
}
long validity = daysText.toLong() * 86400L
final String READER_USER = (opts.readerUsername ?: "grafana-reader").toString().trim()
if (!(READER_USER ==~ /[A-Za-z0-9][A-Za-z0-9._-]{2,49}/)) {
    throw new RuntimeException("Reader username '${READER_USER}' is not valid: 3-50 characters, letters, digits, '.', '_' or '-'.")
}
final String PW_KEY = "secret/" + READER_USER + "-password"
String typedPw = opts.readerPassword?.toString()
if (typedPw != null && typedPw.trim().isEmpty()) typedPw = null

def trustAll = [ new X509TrustManager() {
    X509Certificate[] getAcceptedIssuers() { return null }
    void checkClientTrusted(X509Certificate[] certs, String authType) {}
    void checkServerTrusted(X509Certificate[] certs, String authType) {}
} ] as TrustManager[]
def sc = SSLContext.getInstance("TLS")
sc.init(null, trustAll, new SecureRandom())
HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory())
HttpsURLConnection.setDefaultHostnameVerifier({ String h, SSLSession s -> true } as HostnameVerifier)

def api = { String method, String path, Object payload ->
    def conn = (HttpURLConnection) new URL(applianceUrl + path).openConnection()
    conn.setRequestMethod(method)
    conn.setConnectTimeout(15000)
    conn.setReadTimeout(60000)
    conn.setRequestProperty("Authorization", "Bearer " + bearerToken)
    conn.setRequestProperty("Accept", "application/json")
    conn.setRequestProperty("Content-Type", "application/json")
    if (payload != null) {
        conn.setDoOutput(true)
        conn.outputStream.withWriter("UTF-8") { it << JsonOutput.toJson(payload) }
    }
    int code = conn.responseCode
    String body = (code < 400 ? conn.inputStream : conn.errorStream)?.getText("UTF-8") ?: ""
    def json = null
    try { json = body ? new JsonSlurper().parseText(body) : null } catch (Throwable t) { json = null }
    return [code, json, body]
}
def must = { String what, List r ->
    def (code, json, body) = r
    if (code >= 400 || (json instanceof Map && json.success == false)) {
        def msg = (json instanceof Map) ? (json.msg ?: json.message ?: json.errors ?: body) : body
        throw new RuntimeException("${what} failed (HTTP ${code}): ${msg}")
    }
    return json
}
def enc = { String v -> URLEncoder.encode(v, "UTF-8") }
def done = []

// --- 1. role --------------------------------------------------------------------
def roles = must("Reading roles", api("GET", "/api/roles?max=500&phrase=" + enc(ROLE_NAME), null))?.roles ?: []
def role = roles.find { it.authority?.toString() == ROLE_NAME }
if (!role) {
    role = must("Creating role '${ROLE_NAME}'", api("POST", "/api/roles",
            [role: [authority: ROLE_NAME, description: "Read-only API access for Grafana dashboards (grafana-reader)", roleType: "user"]]))?.role
    done << "role created"
}
READ_PERMS.each { p ->
    must("Setting ${p}=read on '${ROLE_NAME}'", api("PUT", "/api/roles/${role.id}/update-permission", [permissionCode: p, access: "read"]))
}
must("Setting group access", api("PUT", "/api/roles/${role.id}/update-group", [allGroups: true, access: "read"]))
must("Setting cloud access", api("PUT", "/api/roles/${role.id}/update-cloud", [allClouds: true, access: "read"]))
done << "role '${ROLE_NAME}' (${role.id}) permissions set"

// --- 2. OAuth client ------------------------------------------------------------
def clients = must("Reading OAuth clients", api("GET", "/api/clients?max=100", null))?.clients ?: []
def client = clients.find { it.clientId?.toString() == CLIENT_ID }
def clientBody = [client: [clientId: CLIENT_ID, accessTokenValiditySeconds: validity, refreshTokenValiditySeconds: validity]]
if (client) {
    must("Updating OAuth client '${CLIENT_ID}'", api("PUT", "/api/clients/${client.id}", clientBody))
    done << "OAuth client '${CLIENT_ID}' set to ${daysText} days"
} else {
    must("Creating OAuth client '${CLIENT_ID}'", api("POST", "/api/clients", clientBody))
    done << "OAuth client '${CLIENT_ID}' created (${daysText} days)"
}

// --- 3. service user --------------------------------------------------------------
def genPassword = {
    def rnd = new SecureRandom()
    def sets = ["ABCDEFGHJKLMNPQRSTUVWXYZ", "abcdefghijkmnopqrstuvwxyz", "23456789", "!#%+=?@"]
    def all = sets.join("")
    def chars = sets.collect { it[rnd.nextInt(it.length())] }
    (1..20).each { chars << all[rnd.nextInt(all.length())] }
    Collections.shuffle(chars, rnd)
    return chars.join("")
}
def (cCode, cJson, cBody) = api("GET", "/api/cypher/" + PW_KEY, null)
boolean havePw = (cCode == 200 && cJson?.data)
def users = must("Reading users", api("GET", "/api/users?max=100&phrase=" + enc(READER_USER), null))?.users ?: []
def user = users.find { it.username?.toString() == READER_USER }
if (!user) {
    def pw = typedPw ?: genPassword()
    user = must("Creating user '${READER_USER}'", api("POST", "/api/users",
            [user: [username: READER_USER, firstName: "Grafana", lastName: "Reader",
                    email: READER_USER + "@localhost.localdomain", password: pw,
                    roles: [[id: role.id]], receiveNotifications: false]]))?.user
    must("Storing the password in Cypher", api("POST", "/api/cypher/" + PW_KEY + "?type=string", [value: pw]))
    done << "user '${READER_USER}' created (" + (typedPw ? "password from the form" : "random password") + ")"
} else {
    def roleIds = (user.roles ?: []).collect { it.id?.toString() }
    if (!roleIds.contains(role.id.toString())) {
        // PUT replaces the role list, so send the user's current roles plus this one.
        def keep = (user.roles ?: []).findAll { it?.id != null }.collect { [id: it.id] }
        must("Assigning '${ROLE_NAME}' to '${READER_USER}'", api("PUT", "/api/users/${user.id}", [user: [roles: keep + [[id: role.id]]]]))
        done << "role added to '${READER_USER}' (${keep.size()} existing role(s) kept)"
    }
    if (typedPw) {
        must("Setting the password of '${READER_USER}'", api("PUT", "/api/users/${user.id}", [user: [password: typedPw]]))
        must("Storing the password in Cypher", api("POST", "/api/cypher/" + PW_KEY + "?type=string", [value: typedPw]))
        done << "user '${READER_USER}' present, password set from the form"
    } else if (havePw) {
        done << "user '${READER_USER}' present, not created again"
    } else {
        throw new RuntimeException("User '${READER_USER}' already exists but Cypher ${PW_KEY} has no password for it. " +
                "Enter its password (or a new one) in the Reader Password field and order again.")
    }
}

// --- 4. prove it works -------------------------------------------------------------
def (pCode, pJson, pBody) = api("GET", "/api/cypher/" + PW_KEY, null)
def form = "grant_type=password&scope=write&client_id=" + enc(CLIENT_ID) + "&username=" + enc(READER_USER) +
        "&password=" + enc(pJson?.data?.toString() ?: "")
def tconn = (HttpURLConnection) new URL(applianceUrl + "/oauth/token").openConnection()
tconn.setRequestMethod("POST")
tconn.setDoOutput(true)
tconn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
tconn.outputStream.withWriter("UTF-8") { it << form }
int tCode = tconn.responseCode
def tJson = null
try { tJson = new JsonSlurper().parseText((tCode < 400 ? tconn.inputStream : tconn.errorStream)?.getText("UTF-8") ?: "{}") } catch (Throwable t) { tJson = null }
if (tCode >= 400 || !tJson?.access_token) {
    throw new RuntimeException("Setup done but '${READER_USER}' could not log in with client '${CLIENT_ID}' (HTTP ${tCode}).")
}
done << "login test OK, token valid ${((tJson.expires_in ?: 0) as long).intdiv(86400)} days"

println "Grafana reader access: " + done.join("; ") + "."
