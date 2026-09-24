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
// grafana_connect_morpheus.groovy  (v1.1.0 - per-cluster performance rows)
// v1.1.0: one row per Morpheus cluster (HVM, Kubernetes, ...) with the last
//         CPU and memory sample of each host/node (/api/servers?clusterId=N).
//         Morpheus keeps only the latest sample in the API, so these are
//         'now' values, not time series.
// v1.0.0: first version
//
// Catalog item "Grafana - Connect Morpheus". Points a Grafana deployed from the
// "Grafana" catalog item at this Morpheus appliance:
//   1. mints a fresh API token for the read-only service user grafana-reader
//      (password in Cypher secret/grafana-reader-password) and stores it in
//      Cypher secret/grafana-reader-token - tokens of the morph-api client live
//      30 days, so every run renews it
//   2. installs the Infinity data source plugin in Grafana (skipped if present)
//   3. creates or updates the data source "Morpheus" (Bearer token, kept by
//      Grafana in its encrypted secureJsonData)
//   4. creates or overwrites the dashboard "Morpheus Overview", with one
//      performance row per cluster (last CPU / memory sample of each host)
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
//   grafanaPassword : Grafana admin password (Password, optional after the
//                     first run)
// =============================================================================

final String PLUGIN_ID   = "yesoreyeram-infinity-datasource"
final String DS_NAME     = "Morpheus"
final String DASH_UID    = "morpheus-overview"
final String READER_USER = "grafana-reader"

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
def readerPassword = cypherRead("secret/grafana-reader-password")
if (!readerPassword) {
    throw new RuntimeException("Cypher secret/grafana-reader-password is missing - the grafana-reader service user is not set up.")
}
def form = "grant_type=password&scope=write&client_id=morph-api" +
        "&username=" + URLEncoder.encode(READER_USER, "UTF-8") +
        "&password=" + URLEncoder.encode(readerPassword, "UTF-8")
def (tCode, tJson, tBody) = http("POST", applianceUrl + "/oauth/token",
        ["Content-Type": "application/x-www-form-urlencoded"], form)
def readerToken = (tJson instanceof Map) ? tJson.access_token?.toString() : null
if (tCode >= 400 || !readerToken) {
    throw new RuntimeException("Could not get a token for ${READER_USER} (HTTP ${tCode}).")
}
cypherWrite("secret/grafana-reader-token", readerToken)

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
def morpheusHost = new URL(applianceUrl)
def morpheusBase = morpheusHost.protocol + "://" + morpheusHost.authority
def dsBody = [
    name          : DS_NAME,
    type          : PLUGIN_ID,
    access        : "proxy",
    url           : morpheusBase,
    jsonData      : [auth_method: "bearerToken", allowedHosts: [morpheusBase], tlsSkipVerify: true],
    secureJsonData: [bearerToken: readerToken]
]
def (gCode, gJson, gBody) = grafana("GET", "/api/datasources/name/" + URLEncoder.encode(DS_NAME, "UTF-8"), null)
def dsUid
def dsAction
if (gCode == 200 && gJson?.id) {
    dsBody.uid = gJson.uid
    def (uCode, uJson, uBody) = grafana("PUT", "/api/datasources/" + gJson.id, dsBody)
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

// --- 5. dashboard ------------------------------------------------------------------
def ds = [type: PLUGIN_ID, uid: dsUid]
def q = { String path, String root, List cols ->
    [refId: "A", datasource: ds, type: "json", source: "url", parser: "backend", format: "table",
     url: morpheusBase + path, url_options: [method: "GET", data: ""], root_selector: root,
     columns: cols.collect { c -> [selector: c[0], text: c[1], type: (c.size() > 2 ? c[2] : "string")] }]
}
def stat = { int id, String title, String path, int x ->
    [id: id, type: "stat", title: title, datasource: ds, gridPos: [h: 4, w: 6, x: x, y: 0],
     targets: [q(path, "meta", [["total", "Total", "number"]])],
     options: [reduceOptions: [calcs: ["lastNotNull"], fields: "", values: false], colorMode: "none", graphMode: "none"]]
}
def table = { int id, String title, String path, String root, List cols, int x, int y, int w, int h ->
    [id: id, type: "table", title: title, datasource: ds, gridPos: [h: h, w: w, x: x, y: y],
     targets: [q(path, root, cols)]]
}
// Performance rows: one per cluster, built from the clusters the caller can see.
def perfPanels = []
int pid = 100
int py = 32
def clusterList = (morpheusGet("/api/clusters?max=50")?.clusters ?: []).sort { it.name?.toString() }
clusterList.each { c ->
    def path = "/api/servers?max=200&clusterId=" + c.id
    def cols = [["name", "Host", "string"], ["powerState", "Power", "string"],
                ["stats.cpuUsage", "CPU", "number"], ["stats.usedMemory", "Used memory", "number"],
                ["stats.maxMemory", "Max memory", "number"], ["stats.ts", "Sampled at", "string"]]
    def memPct = [id: "calculateField", options: [mode: "binary", alias: "Memory",
                  binary: [left: "Used memory", operator: "/", right: "Max memory"], replaceFields: false]]
    perfPanels << [id: pid++, type: "row", title: "${c.name} - performance (last sample Morpheus holds)".toString(),
                   collapsed: false, gridPos: [h: 1, w: 24, x: 0, y: py], panels: []]
    perfPanels << [id: pid++, type: "bargauge", title: "CPU %", datasource: ds, gridPos: [h: 8, w: 6, x: 0, y: py + 1],
                   targets: [q(path, "servers", cols)],
                   fieldConfig: [defaults: [unit: "percent", min: 0, max: 100, decimals: 1], overrides: []],
                   options: [reduceOptions: [values: true, calcs: [], fields: "/^CPU\$/"], orientation: "horizontal",
                             displayMode: "basic", showUnfilled: true]]
    perfPanels << [id: pid++, type: "bargauge", title: "Memory used", datasource: ds, gridPos: [h: 8, w: 6, x: 6, y: py + 1],
                   targets: [q(path, "servers", cols)], transformations: [memPct],
                   fieldConfig: [defaults: [unit: "percentunit", min: 0, max: 1, decimals: 1], overrides: []],
                   options: [reduceOptions: [values: true, calcs: [], fields: "/^Memory\$/"], orientation: "horizontal",
                             displayMode: "basic", showUnfilled: true]]
    perfPanels << [id: pid++, type: "table", title: "Hosts / nodes", datasource: ds, gridPos: [h: 8, w: 12, x: 12, y: py + 1],
                   targets: [q(path, "servers", cols)], transformations: [memPct],
                   fieldConfig: [defaults: [:], overrides: [
                       [matcher: [id: "byName", options: "CPU"], properties: [[id: "unit", value: "percent"], [id: "decimals", value: 1]]],
                       [matcher: [id: "byName", options: "Used memory"], properties: [[id: "unit", value: "bytes"]]],
                       [matcher: [id: "byName", options: "Max memory"], properties: [[id: "unit", value: "bytes"]]],
                       [matcher: [id: "byName", options: "Memory"], properties: [[id: "unit", value: "percentunit"], [id: "decimals", value: 1]]]]]]
    py += 9
}

def dashboard = [
    uid: DASH_UID, title: "Morpheus Overview", tags: ["morpheus"], timezone: "browser",
    refresh: "5m", schemaVersion: 39, time: [from: "now-24h", to: "now"],
    panels: [
        stat(1, "Apps", "/api/apps?max=1", 0),
        stat(2, "Hosts and VMs", "/api/servers?max=1", 6),
        stat(3, "Kubernetes clusters and others", "/api/clusters?max=1", 12),
        stat(4, "Clouds", "/api/zones?max=1", 18),
        table(5, "Apps", "/api/apps?max=200", "apps",
              [["name", "Name"], ["type", "Type"], ["status", "Status"], ["appContext", "Environment"], ["group.name", "Group"]],
              0, 4, 12, 8),
        table(6, "Clusters", "/api/clusters?max=200", "clusters",
              [["name", "Name"], ["type.name", "Type"], ["status", "Status"], ["zone.name", "Cloud"]],
              12, 4, 12, 8),
        table(7, "Hosts and VMs", "/api/servers?max=500", "servers",
              [["name", "Name"], ["powerState", "Power"], ["status", "Status"], ["zone.name", "Cloud"],
               ["computeServerType.name", "Type"], ["osType", "OS"]],
              0, 12, 24, 10),
        table(8, "Recent activity", "/api/activity?max=50", "activity",
              [["ts", "Time"], ["name", "Object"], ["activityType", "Type"], ["message", "Message"], ["userName", "User"]],
              0, 22, 24, 10)
    ] + perfPanels
]
def (dCode, dJson, dBody) = grafana("POST", "/api/dashboards/db", [dashboard: dashboard, overwrite: true, message: "Morpheus catalog: Grafana - Connect Morpheus"])
if (dCode >= 400) {
    throw new RuntimeException("Importing the dashboard failed (HTTP ${dCode}): ${apiMsg(dJson, dBody)}")
}

println "Grafana ${grafanaUrl}: plugin ${pluginAction}, data source '${DS_NAME}' ${dsAction}, dashboard ${grafanaUrl}${dJson?.url ?: '/d/' + DASH_UID}; grafana-reader token renewed."
