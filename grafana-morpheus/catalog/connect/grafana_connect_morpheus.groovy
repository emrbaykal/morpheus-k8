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
// grafana_connect_morpheus.groovy  (v1.8.0 - dashboards from the published JSON files)
// fix:    data sources are updated through /api/datasources/uid/<uid>; Grafana 13 answers
//         404 on the numeric-id path, so a second run failed on an existing data source.
//         Data source names in URL paths encode a space as %20 (was '+', so a name with
//         spaces was not found and a re-run skipped the HVM dashboards).
// v1.8.0: the dashboards are no longer built in this script. The task downloads the
//         published JSON files (Dashboard Source URL, default the morpheus-k8
//         repository on GitHub) and imports them with Grafana's import API, so the
//         catalog and a manual setup show the same dashboards. New optional form
//         field 'HVM Hosts Prometheus URL': data source 'Prometheus HVM Hosts' and
//         the dashboards HVM Hosts and VMs, HVM Bottlenecks and HVM Capacity
//         (node_exporter + prometheus-libvirt-exporter on the HVM hosts).
// v1.6.1: every table keeps the column order defined here (Infinity's backend
//         parser sorts columns alphabetically, which pushed Name/VM/Host to
//         the right); CPU / Memory bar labels show only the host name.
// v1.6.0: runs as the second task of the "Grafana - Connect Morpheus" workflow,
//         after grafana_setup_reader.groovy (the separate Setup item is gone).
//         The reader username comes from the form field readerUsername
//         (default grafana-reader); password and token are kept in Cypher
//         secret/<user>-password and secret/<user>-token.
// v1.5.0: Prometheus URL and the Morpheus URL Grafana should call come from the
//         form (Prometheus empty = no pods dashboard; Morpheus empty = the
//         appliance URL). A Prometheus data source that does not answer is
//         deleted instead of being left broken; data source renamed to
//         'Prometheus' (the old 'Prometheus HKS' is removed). Clusters without
//         hosts get no performance row.
// v1.4.0: one 'Virtual machines' table per cloud (/api/servers?vm=true, last
//         sample: CPU, memory, disk, network, IOPS); data source 'Prometheus HKS'
//         (kube-prometheus in the cluster Grafana runs in) and a second
//         dashboard 'Kubernetes Pods' with per-pod CPU, memory, network and
//         PVC usage over time, plus node CPU/memory. Prometheus is optional:
//         if it does not answer, that part is skipped and reported.
// v1.3.0: the reader token is minted with the dedicated OAuth client 'grafana'
//         (Administration > Settings > Clients, access token validity
//         31536000 s = 1 year) instead of morph-api (30 days). Run this item
//         once a year, or after rotating the grafana-reader password.
// v1.2.0: host tables gain network Tx/Rx, IOPS and swap; new rows for the
//         Morpheus appliance itself (/api/health: CPU, memory, storage,
//         Elasticsearch, RabbitMQ, database), cloud sync status (/api/zones)
//         and monitoring checks / incidents. Needs admin-health and
//         monitoring = read on the Grafana Reader role.
// v1.1.0: one row per Morpheus cluster (HVM, Kubernetes, ...) with the last
//         CPU and memory sample of each host/node (/api/servers?clusterId=N).
//         Morpheus keeps only the latest sample in the API, so these are
//         'now' values, not time series.
// v1.0.0: first version
//
// Catalog item "Grafana - Connect Morpheus". Points a Grafana deployed from the
// "Grafana" catalog item at this Morpheus appliance:
//   1. mints a fresh API token for the read-only service user (readerUsername,
//      password in Cypher secret/<user>-password) with the OAuth client
//      'grafana' and stores it in Cypher secret/<user>-token
//   2. installs the Infinity data source plugin in Grafana (skipped if present)
//   3. creates or updates the data source "Morpheus" (Bearer token, kept by
//      Grafana in its encrypted secureJsonData)
//   4. imports the published dashboards: Morpheus Overview (Infinity), Kubernetes
//      Pods (Prometheus URL) and the three HVM dashboards (HVM Hosts Prometheus URL)
// Running it again is safe: every step is create-or-update.
//
// Auth: Morpheus calls (Cypher) run on the executing user's token
// (morpheus.apiAccessToken). Grafana calls use the Grafana admin login.
// The Grafana admin password is kept in Cypher secret/grafana-admin/<app> so a
// scheduled run can renew the token without the form; leave the form field
// empty to use the stored one.
//
// Inputs (Form Inputs, customOptions):
//   grafanaApp      : app name (Select, Option List "Helm Apps")
//   prometheusUrl   : optional, e.g. http://prometheus-k8s.monitoring.svc:9090
//                     (kube-prometheus in the cluster Grafana runs in); empty =
//                     skip the Kubernetes Pods dashboard
//   morpheusUrl     : optional, the Morpheus URL as Grafana can reach it (single
//                     node, load balancer, ...); empty = the appliance URL
//   grafanaPassword : Grafana admin password (Password, optional after the
//                     first run)
//   readerUsername  : read-only Morpheus API user (default grafana-reader)
//   hostPrometheusUrl : optional, Prometheus that scrapes the HVM hosts; empty =
//                     skip the HVM dashboards
//   dashboardSource : optional, http(s) folder with the dashboard JSON files;
//                     empty = the morpheus-k8 repository on GitHub
// =============================================================================

final String PLUGIN_ID   = "yesoreyeram-infinity-datasource"
final String DS_NAME     = "Morpheus"
final String PROM_NAME   = "Prometheus"
final String OLD_PROM    = "Prometheus HKS"
final String HOST_PROM_NAME = "Prometheus HVM Hosts"
final String DEFAULT_DASH_SOURCE = "https://raw.githubusercontent.com/emrbaykal/morpheus-k8/main/grafana-morpheus/dashboards"
final String OVERVIEW_FILE = "morpheus-overview.json"
final String PODS_FILE   = "kubernetes-pods.json"
final List   HVM_FILES   = ["hvm-hosts-vms.json", "hvm-bottlenecks.json", "hvm-capacity.json"]
final String OAUTH_CLIENT = "grafana"

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
// Reader user from the form (same field the setup step uses); its password and
// token live in Cypher under secret/<user>-password and secret/<user>-token.
final String READER_USER = (opts.readerUsername ?: "grafana-reader").toString().trim()
final String PW_KEY      = "secret/" + READER_USER + "-password"
final String TOKEN_KEY   = "secret/" + READER_USER + "-token"

def applianceUrl = morpheus.applianceUrl?.toString()?.replaceAll('/+$', '')
def bearerToken  = morpheus.apiAccessToken?.toString()
if (!applianceUrl || !bearerToken) {
    throw new RuntimeException("Appliance URL or the executing user's API token is not available to this task.")
}

// Trust self-signed certificates (appliance).
def trustAll = [ new X509TrustManager() {
    X509Certificate[] getAcceptedIssuers() { return null }
    void checkClientTrusted(X509Certificate[] certs, String authType) {}
    void checkServerTrusted(X509Certificate[] certs, String authType) {}
} ] as TrustManager[]
def sc = SSLContext.getInstance("TLS")
sc.init(null, trustAll, new java.security.SecureRandom())
HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory())
HttpsURLConnection.setDefaultHostnameVerifier({ String h, SSLSession s -> true } as HostnameVerifier)

// One HTTP helper for both Morpheus and Grafana. Returns [code, json, body].
def http = { String method, String url, Map headers, String payload ->
    def conn = (HttpURLConnection) new URL(url).openConnection()
    conn.setRequestMethod(method)
    conn.setConnectTimeout(15000)
    conn.setReadTimeout(120000)
    conn.setRequestProperty("Accept", "application/json")
    headers.each { k, v -> conn.setRequestProperty(k.toString(), v.toString()) }
    if (payload != null) {
        conn.setDoOutput(true)
        conn.outputStream.withWriter("UTF-8") { it << payload }
    }
    int code = conn.responseCode
    String body = (code < 400 ? conn.inputStream : conn.errorStream)?.getText("UTF-8") ?: ""
    def json = null
    try { json = body ? new JsonSlurper().parseText(body) : null } catch (Throwable t) { json = null }
    return [code, json, body]
}
def apiMsg = { json, String body ->
    (json instanceof Map) ? (json.msg ?: json.message ?: json.errors ?: body) : body
}
def morpheusHeaders = ["Authorization": "Bearer " + bearerToken, "Content-Type": "application/json"]
def morpheusGet = { String path ->
    def (code, json, body) = http("GET", applianceUrl + path, morpheusHeaders, null)
    if (code >= 400 || (json instanceof Map && json.success == false)) {
        throw new RuntimeException("Morpheus GET ${path} failed (HTTP ${code}): ${apiMsg(json, body)}")
    }
    return json
}
def cypherWrite = { String key, String value ->
    def (code, json, body) = http("POST", applianceUrl + "/api/cypher/" + key + "?type=string",
            morpheusHeaders, JsonOutput.toJson([value: value]))
    if (code >= 400 || (json instanceof Map && json.success == false)) {
        throw new RuntimeException("Cypher write ${key} failed (HTTP ${code}): ${apiMsg(json, body)}")
    }
}
def cypherRead = { String key ->
    def (code, json, body) = http("GET", applianceUrl + "/api/cypher/" + key, morpheusHeaders, null)
    if (code == 404) { return null }
    if (code >= 400 || (json instanceof Map && json.success == false)) {
        throw new RuntimeException("Cypher read ${key} failed (HTTP ${code}): ${apiMsg(json, body)}")
    }
    return json?.data?.toString()
}

// --- 1. which Grafana ---------------------------------------------------------
def appName = opts.grafanaApp?.toString()?.trim()
if (!appName) {
    throw new RuntimeException("Select the Grafana app to connect.")
}
def apps = morpheusGet("/api/apps?max=200&name=" + URLEncoder.encode(appName, "UTF-8"))?.apps ?: []
def app = apps.find { it?.name?.toString() == appName }
if (!app) {
    throw new RuntimeException("App '${appName}' was not found or you have no access to it.")
}
def m = (app.description ?: "") =~ /(https?:\/\/[^\s)]+:\d+)/
if (!(app.description ?: "").contains("Grafana") || !m.find()) {
    throw new RuntimeException("App '${appName}' is not a Grafana app from the Grafana catalog item (no Grafana URL in its description).")
}
def grafanaUrl = m.group(1)

def adminKey = "secret/grafana-admin/" + appName
def grafanaPassword = opts.grafanaPassword?.toString()
if (grafanaPassword) {
    cypherWrite(adminKey, grafanaPassword)
} else {
    grafanaPassword = cypherRead(adminKey)
    if (!grafanaPassword) {
        throw new RuntimeException("Enter the Grafana admin password - none is stored yet for '${appName}'.")
    }
}
def grafanaHeaders = [
    "Authorization": "Basic " + ("admin:" + grafanaPassword).bytes.encodeBase64().toString(),
    "Content-Type" : "application/json"
]
// Data source names in a URL path: URLEncoder writes a space as '+', which Grafana reads
// literally in a path, so "Prometheus HVM Hosts" was not found and a re-run failed.
def pathName = { String n -> URLEncoder.encode(n, "UTF-8").replace("+", "%20") }
def grafana = { String method, String path, payload ->
    http(method, grafanaUrl + path, grafanaHeaders, payload == null ? null : JsonOutput.toJson(payload))
}
def (hCode, hJson, hBody) = grafana("GET", "/api/org", null)
if (hCode == 401) {
    throw new RuntimeException("Grafana at ${grafanaUrl} rejected the admin password.")
}
if (hCode >= 400) {
    throw new RuntimeException("Grafana at ${grafanaUrl} answered HTTP ${hCode}: ${apiMsg(hJson, hBody)}")
}

// --- 2. fresh token for grafana-reader ------------------------------------------
def readerPassword = cypherRead(PW_KEY)
if (!readerPassword) {
    throw new RuntimeException("Cypher ${PW_KEY} is missing - the ${READER_USER} service user is not set up.")
}
def form = "grant_type=password&scope=write&client_id=" + URLEncoder.encode(OAUTH_CLIENT, "UTF-8") +
        "&username=" + URLEncoder.encode(READER_USER, "UTF-8") +
        "&password=" + URLEncoder.encode(readerPassword, "UTF-8")
def (tCode, tJson, tBody) = http("POST", applianceUrl + "/oauth/token",
        ["Content-Type": "application/x-www-form-urlencoded"], form)
def readerToken = (tJson instanceof Map) ? tJson.access_token?.toString() : null
if (tCode >= 400 || !readerToken) {
    throw new RuntimeException("Could not get a token for ${READER_USER} (HTTP ${tCode}).")
}
cypherWrite(TOKEN_KEY, readerToken)

// --- 3. Infinity plugin ------------------------------------------------------------
def (pCode, pJson, pBody) = grafana("GET", "/api/plugins/" + PLUGIN_ID + "/settings", null)
def pluginAction = "already installed"
if (pCode == 404) {
    def (iCode, iJson, iBody) = grafana("POST", "/api/plugins/" + PLUGIN_ID + "/install", [:])
    if (iCode >= 400 && iCode != 409) {
        throw new RuntimeException("Installing the Infinity plugin failed (HTTP ${iCode}): ${apiMsg(iJson, iBody)}. " +
                "Grafana needs internet access to grafana.com for this step.")
    }
    pluginAction = "installed"
}

// --- 4. data source ----------------------------------------------------------------
def morpheusUrlOpt = opts.morpheusUrl?.toString()?.trim()?.replaceAll('/+$', '')
if (morpheusUrlOpt && !(morpheusUrlOpt ==~ /^https?:\/\/[^\s\/]+$/)) {
    throw new RuntimeException("Morpheus URL must look like https://morpheus.example.local (no path), got '${morpheusUrlOpt}'.")
}
def morpheusHost = new URL(morpheusUrlOpt ?: applianceUrl)
def morpheusBase = morpheusHost.protocol + "://" + morpheusHost.authority
def dsBody = [
    name          : DS_NAME,
    type          : PLUGIN_ID,
    access        : "proxy",
    url           : morpheusBase,
    jsonData      : [auth_method: "bearerToken", allowedHosts: [morpheusBase], tlsSkipVerify: true],
    secureJsonData: [bearerToken: readerToken]
]
def (gCode, gJson, gBody) = grafana("GET", "/api/datasources/name/" + pathName(DS_NAME), null)
def dsUid
def dsAction
if (gCode == 200 && gJson?.uid) {
    dsBody.uid = gJson.uid
    def (uCode, uJson, uBody) = grafana("PUT", "/api/datasources/uid/" + gJson.uid, dsBody)
    if (uCode >= 400) {
        throw new RuntimeException("Updating data source '${DS_NAME}' failed (HTTP ${uCode}): ${apiMsg(uJson, uBody)}")
    }
    dsUid = gJson.uid
    dsAction = "updated"
} else {
    def (cCode, cJson, cBody) = grafana("POST", "/api/datasources", dsBody)
    if (cCode >= 400) {
        throw new RuntimeException("Creating data source '${DS_NAME}' failed (HTTP ${cCode}): ${apiMsg(cJson, cBody)}")
    }
    dsUid = cJson?.datasource?.uid ?: cJson?.uid
    dsAction = "created"
}

// --- 5. dashboards: the published JSON files, imported with Grafana's import API ---
// The same files a reader imports by hand, so the catalog and the manual setup show
// the same dashboards. Each file names its data source as an __inputs entry.
def dashSource = (opts.dashboardSource?.toString()?.trim() ?: DEFAULT_DASH_SOURCE).replaceAll('/+$', '')
if (!(dashSource ==~ /^https?:\/\/\S+$/)) {
    throw new RuntimeException("Dashboard source must be an http(s) URL of the folder with the dashboard JSON files, got '${dashSource}'.")
}
def fetchDashboard = { String file ->
    def (fCode, fJson, fBody) = http("GET", dashSource + "/" + file, [:], null)
    if (fCode != 200 || !(fJson instanceof Map) || !fJson.panels) {
        throw new RuntimeException("Could not read ${dashSource}/${file} (HTTP ${fCode}). The Morpheus appliance must reach " +
                "the dashboard source; set 'Dashboard Source URL' to a location it can reach.")
    }
    return fJson
}
def importDashboard = { String file, Map inputs ->
    def d = fetchDashboard(file)
    d.remove("id")
    def body = [dashboard: d, overwrite: true, folderUid: "",
                inputs: inputs.collect { k, v -> [name: k, type: "datasource", pluginId: v.type, value: v.uid] }]
    def (iCode, iJson, iBody) = grafana("POST", "/api/dashboards/import", body)
    if (iCode >= 400) {
        throw new RuntimeException("Importing ${file} failed (HTTP ${iCode}): ${apiMsg(iJson, iBody)}")
    }
    return grafanaUrl + (iJson?.importedUrl ?: "/d/" + d.uid)
}
def imported = []
imported << importDashboard(OVERVIEW_FILE, [DS_MORPHEUS: [type: PLUGIN_ID, uid: dsUid]])

// Prometheus data source: create or update, keep it only if it answers.
def upsertPrometheus = { String name, String url ->
    def body = [name: name, type: "prometheus", access: "proxy", url: url, jsonData: [timeInterval: "30s"]]
    def (gc, gj, gb) = grafana("GET", "/api/datasources/name/" + pathName(name), null)
    def uid = null
    if (gc == 200 && gj?.uid) {
        body.uid = gj.uid
        def (uc, uj, ub) = grafana("PUT", "/api/datasources/uid/" + gj.uid, body)
        if (uc < 400) { uid = gj.uid }
    } else {
        def (cc, cj, cb) = grafana("POST", "/api/datasources", body)
        if (cc < 400) { uid = cj?.datasource?.uid ?: cj?.uid }
    }
    if (!uid) { return null }
    def (hc, hj, hb) = grafana("GET", "/api/datasources/uid/" + uid + "/health", null)
    if (hc == 200 && hj?.status?.toString() == "OK") { return uid }
    grafana("DELETE", "/api/datasources/uid/" + uid, null)
    return null
}
// Existing data source of that name, if it answers (used when the URL field is empty).
def existingPrometheus = { String name ->
    def (gc, gj, gb) = grafana("GET", "/api/datasources/name/" + pathName(name), null)
    if (gc != 200 || !gj?.uid) { return null }
    def (hc, hj, hb) = grafana("GET", "/api/datasources/uid/" + gj.uid + "/health", null)
    return (hc == 200 && hj?.status?.toString() == "OK") ? gj.uid : null
}

// --- 6. Prometheus of the Kubernetes cluster (optional) ------------------------------
// Remove the data source name used before v1.5.0.
def (ogCode, ogJson, ogBody) = grafana("GET", "/api/datasources/name/" + pathName(OLD_PROM), null)
if (ogCode == 200 && ogJson?.uid) { grafana("DELETE", "/api/datasources/uid/" + ogJson.uid, null) }
def promUrl = opts.prometheusUrl?.toString()?.trim()?.replaceAll('/+$', '')
def promUid = promUrl ? upsertPrometheus(PROM_NAME, promUrl) : existingPrometheus(PROM_NAME)
def promNote
if (promUid) {
    imported << importDashboard(PODS_FILE, [DS_PROMETHEUS: [type: "prometheus", uid: promUid]])
    promNote = "data source '${PROM_NAME}' ready"
} else {
    promNote = promUrl ? "data source '${PROM_NAME}' (${promUrl}) could not be saved or did not answer - pods dashboard skipped" : "no Prometheus URL - pods dashboard skipped"
}

// --- 7. Prometheus of the HVM hosts (optional) ---------------------------------------
// node_exporter + prometheus-libvirt-exporter on every HVM host, scraped by a
// Prometheus with the jobs 'node' and 'libvirt' (prometheus-chart, value hostScrape).
def hostPromUrl = opts.hostPrometheusUrl?.toString()?.trim()?.replaceAll('/+$', '')
def hostPromUid = hostPromUrl ? upsertPrometheus(HOST_PROM_NAME, hostPromUrl) : existingPrometheus(HOST_PROM_NAME)
def hostNote
if (hostPromUid) {
    HVM_FILES.each { f -> imported << importDashboard(f, [DS_PROMETHEUS: [type: "prometheus", uid: hostPromUid]]) }
    hostNote = "data source '${HOST_PROM_NAME}' ready, HVM dashboards imported"
} else {
    hostNote = hostPromUrl ? "data source '${HOST_PROM_NAME}' (${hostPromUrl}) could not be saved or did not answer - HVM dashboards skipped" : "no HVM hosts Prometheus URL - HVM dashboards skipped"
}

println "Grafana ${grafanaUrl}: plugin ${pluginAction}, data source '${DS_NAME}' ${dsAction}; ${promNote}; ${hostNote}; " +
        "dashboards from ${dashSource}: ${imported.join(', ')}; ${READER_USER} token renewed."
