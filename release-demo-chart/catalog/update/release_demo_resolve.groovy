import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import java.security.cert.X509Certificate

// =============================================================================
// release_demo_resolve.groovy  (v1.0.0 - first version)
//
// Task 1 of the "Release Demo - Update" workflow. Validates the order form and
// turns it into what the helm step needs:
//   - the selected app must exist and be a Helm app -> release name = app name
//   - the selected cluster (form value = cloud/zone id) -> Morpheus cluster id
//   - replicas must be 1-5
//
// Morpheus has no API that triggers a Helm app Upgrade (checked 2026-09-24,
// 9.0.2), so task 2 (release_demo_helm_upgrade.sh, Shell, Local, GIT REPO =
// morpheus-k8) runs `helm upgrade` itself on Morpheus' cached copy of the repo.
//
// Auth: the executing user's own token (morpheus.apiAccessToken).
// Task CODE must be `rdResolve`, RESULT TYPE = JSON. The only stdout is one
// JSON object: {"release": "...", "clusterId": n, "replicas": n}.
// The cluster token is NOT fetched here on purpose: task results are shown in
// the execution history, so the helm step fetches it itself.
//
// Inputs (Form Inputs, customOptions):
//   helmApp  : app id    (Select, Option List "Helm Apps")
//   cluster  : zone id   (Select, Option List "Kubernetes Clusters")
//   replicas : 1-5       (Text)
// =============================================================================

def opts = [
    { customOptions },
    { morpheus.customOptions },
    { morpheus['customOptions'] },
].findResult { tryIt ->
    try { def v = tryIt(); (v instanceof Map && !v.isEmpty()) ? v : null } catch (Throwable t) { null }
}
if (opts == null) {
    throw new RuntimeException("customOptions not accessible from this Groovy task context.")
}

def applianceUrl = morpheus.applianceUrl?.toString()?.replaceAll('/+$', '')
def bearerToken  = morpheus.apiAccessToken?.toString()
if (!applianceUrl || !bearerToken) {
    throw new RuntimeException("Appliance URL or the executing user's API token is not available to this task.")
}

// Trust the appliance's self-signed certificate.
def trustAll = [ new X509TrustManager() {
    X509Certificate[] getAcceptedIssuers() { return null }
    void checkClientTrusted(X509Certificate[] certs, String authType) {}
    void checkServerTrusted(X509Certificate[] certs, String authType) {}
} ] as TrustManager[]
def sc = SSLContext.getInstance("TLS")
sc.init(null, trustAll, new java.security.SecureRandom())
HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory())
HttpsURLConnection.setDefaultHostnameVerifier({ String h, SSLSession s -> true } as HostnameVerifier)

def apiGet = { String path ->
    def conn = (HttpURLConnection) new URL(applianceUrl + path).openConnection()
    conn.setRequestMethod("GET")
    conn.setRequestProperty("Authorization", "Bearer " + bearerToken)
    conn.setRequestProperty("Accept", "application/json")
    conn.setConnectTimeout(15000)
    conn.setReadTimeout(30000)
    int code = conn.responseCode
    String body = (code < 400 ? conn.inputStream : conn.errorStream)?.getText("UTF-8") ?: ""
    def json = null
    try { json = body ? new JsonSlurper().parseText(body) : null } catch (Throwable t) { json = null }
    if (code >= 400 || (json instanceof Map && json.success == false)) {
        def msg = (json instanceof Map) ? (json.msg ?: json.message ?: json.errors) : body
        throw new RuntimeException("GET ${path} failed (HTTP ${code}): ${msg}")
    }
    return json
}

// --- inputs ------------------------------------------------------------------
def appId = opts.helmApp?.toString()?.trim()
def zoneId = opts.cluster?.toString()?.trim()
def replicasText = opts.replicas?.toString()?.trim()
if (!appId || !appId.isLong()) {
    throw new RuntimeException("Select the Helm app to update.")
}
if (!zoneId || !zoneId.isLong()) {
    throw new RuntimeException("Select the Kubernetes cluster the app runs on.")
}
if (!replicasText || !replicasText.isInteger() || replicasText.toInteger() < 1 || replicasText.toInteger() > 5) {
    throw new RuntimeException("Replicas must be a number from 1 to 5 (got '${replicasText}').")
}

// --- the app -----------------------------------------------------------------
def app = apiGet("/api/apps/${appId}")?.app
if (!app) {
    throw new RuntimeException("App ${appId} was not found or you have no access to it.")
}
if (app.type?.toString() != "helm") {
    throw new RuntimeException("App '${app.name}' is of type '${app.type}', not a Helm app.")
}

// --- the cluster -------------------------------------------------------------
def clusters = apiGet("/api/clusters?max=200")?.clusters ?: []
def cluster = clusters.find { it?.zone?.id?.toString() == zoneId }
if (!cluster) {
    throw new RuntimeException("No Kubernetes cluster found for cloud id ${zoneId}.")
}

println JsonOutput.toJson([
    release  : app.name.toString(),
    clusterId: cluster.id as Long,
    replicas : replicasText.toInteger()
])
