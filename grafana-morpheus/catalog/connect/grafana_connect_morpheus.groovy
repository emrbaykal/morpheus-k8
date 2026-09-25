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
// grafana_connect_morpheus.groovy  (v1.6.1 - column order)
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
//   prometheusUrl   : optional, e.g. http://prometheus-k8s.monitoring.svc:9090
//                     (kube-prometheus in the cluster Grafana runs in); empty =
//                     skip the Kubernetes Pods dashboard
//   morpheusUrl     : optional, the Morpheus URL as Grafana can reach it (single
//                     node, load balancer, ...); empty = the appliance URL
//   grafanaPassword : Grafana admin password (Password, optional after the
//                     first run)
//   readerUsername  : read-only Morpheus API user (default grafana-reader)
// =============================================================================

final String PLUGIN_ID   = "yesoreyeram-infinity-datasource"
final String DS_NAME     = "Morpheus"
final String DASH_UID    = "morpheus-overview"
final String PROM_NAME   = "Prometheus"
final String OLD_PROM    = "Prometheus HKS"
final String PODS_UID    = "kubernetes-pods"
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
// The Infinity backend parser returns columns in alphabetical order; this
// "organize" step puts them back in the order they are listed here.
def ordered = { List names, List hide = [] ->
    def idx = [:]
    names.eachWithIndex { n, i -> idx[n] = i }
    [id: "organize", options: [indexByName: idx, excludeByName: hide.collectEntries { [(it): true] }, renameByName: [:]]]
}
def table = { int id, String title, String path, String root, List cols, int x, int y, int w, int h ->
    [id: id, type: "table", title: title, datasource: ds, gridPos: [h: h, w: w, x: x, y: y],
     targets: [q(path, root, cols)], transformations: [ordered(cols.collect { it[1] })]]
}
// Performance rows: one per cluster, built from the clusters the caller can see.
def perfPanels = []
int pid = 100
int py = 32
def clusterList = (morpheusGet("/api/clusters?max=50")?.clusters ?: []).sort { it.name?.toString() }
clusterList.each { c ->
    def hostCount = morpheusGet("/api/servers?max=1&clusterId=" + c.id)?.meta?.total ?: 0
    if (hostCount == 0) { return }
    def path = "/api/servers?max=200&clusterId=" + c.id
    def cols = [["name", "Host", "string"], ["powerState", "Power", "string"],
                ["stats.cpuUsage", "CPU", "number"], ["stats.usedMemory", "Used memory", "number"],
                ["stats.maxMemory", "Max memory", "number"], ["stats.netTxUsage", "Net Tx", "number"],
                ["stats.netRxUsage", "Net Rx", "number"], ["stats.totalIOPS", "IOPS", "number"],
                ["stats.usedSwap", "Swap used", "number"], ["stats.ts", "Sampled at", "string"]]
    def memPct = [id: "calculateField", options: [mode: "binary", alias: "Memory",
                  binary: [left: "Used memory", operator: "/", right: "Max memory"], replaceFields: false]]
    perfPanels << [id: pid++, type: "row", title: "${c.name} - performance (last sample Morpheus holds)".toString(),
                   collapsed: false, gridPos: [h: 1, w: 24, x: 0, y: py], panels: []]
    // Bar labels come from the text columns, so only the host name is kept.
    def barLabel = ordered(["Host"], ["Power", "Sampled at"])
    def hostOrder = ["Host", "Power", "CPU", "Memory", "Used memory", "Max memory", "Net Tx", "Net Rx",
                     "IOPS", "Swap used", "Sampled at"]
    perfPanels << [id: pid++, type: "bargauge", title: "CPU %", datasource: ds, gridPos: [h: 8, w: 6, x: 0, y: py + 1],
                   targets: [q(path, "servers", cols)], transformations: [barLabel],
                   fieldConfig: [defaults: [unit: "percent", min: 0, max: 100, decimals: 1], overrides: []],
                   options: [reduceOptions: [values: true, calcs: [], fields: "/^CPU\$/"], orientation: "horizontal",
                             displayMode: "basic", showUnfilled: true]]
    perfPanels << [id: pid++, type: "bargauge", title: "Memory used", datasource: ds, gridPos: [h: 8, w: 6, x: 6, y: py + 1],
                   targets: [q(path, "servers", cols)], transformations: [memPct, barLabel],
                   fieldConfig: [defaults: [unit: "percentunit", min: 0, max: 1, decimals: 1], overrides: []],
                   options: [reduceOptions: [values: true, calcs: [], fields: "/^Memory\$/"], orientation: "horizontal",
                             displayMode: "basic", showUnfilled: true]]
    perfPanels << [id: pid++, type: "table", title: "Hosts / nodes", datasource: ds, gridPos: [h: 8, w: 12, x: 12, y: py + 1],
                   targets: [q(path, "servers", cols)], transformations: [memPct, ordered(hostOrder)],
                   fieldConfig: [defaults: [:], overrides: [
                       [matcher: [id: "byName", options: "CPU"], properties: [[id: "unit", value: "percent"], [id: "decimals", value: 1]]],
                       [matcher: [id: "byName", options: "Used memory"], properties: [[id: "unit", value: "bytes"]]],
                       [matcher: [id: "byName", options: "Max memory"], properties: [[id: "unit", value: "bytes"]]],
                       [matcher: [id: "byName", options: "Swap used"], properties: [[id: "unit", value: "bytes"]]],
                       [matcher: [id: "byName", options: "Memory"], properties: [[id: "unit", value: "percentunit"], [id: "decimals", value: 1]]]]]]
    py += 9
}

// Morpheus appliance health, clouds, monitoring.
def healthCols = [["cpu.cpuTotalLoad", "CPU", "number"], ["memory.memoryPercent", "JVM memory", "number"],
                  ["memory.systemMemoryPercent", "System memory", "number"], ["storage.percent", "Storage", "number"],
                  ["elastic.status", "Elasticsearch", "string"], ["rabbit.status", "RabbitMQ", "string"],
                  ["database.status", "Database", "string"]]
def healthStat = { String title, String field, String unit, Number max, int x ->
    [id: pid++, type: "stat", title: title, datasource: ds, gridPos: [h: 4, w: 4, x: x, y: py + 1],
     targets: [q("/api/health", "health", healthCols)],
     fieldConfig: [defaults: [unit: unit, min: 0, max: max, decimals: 1], overrides: []],
     options: [reduceOptions: [calcs: ["lastNotNull"], fields: "/^" + field + "\$/", values: false],
               colorMode: "none", graphMode: "none", textMode: "value"]]
}
perfPanels << [id: pid++, type: "row", title: "Morpheus appliance", collapsed: false,
               gridPos: [h: 1, w: 24, x: 0, y: py], panels: []]
perfPanels << healthStat("CPU", "CPU", "percent", 100, 0)
perfPanels << healthStat("JVM memory", "JVM memory", "percentunit", 1, 4)
perfPanels << healthStat("System memory", "System memory", "percentunit", 1, 8)
perfPanels << healthStat("Storage", "Storage", "percent", 100, 12)
perfPanels << [id: pid++, type: "table", title: "Services", datasource: ds, gridPos: [h: 4, w: 8, x: 16, y: py + 1],
               targets: [q("/api/health", "health", healthCols)],
               transformations: [ordered(["Database", "Elasticsearch", "RabbitMQ"], ["CPU", "JVM memory", "System memory", "Storage"])]]
perfPanels << table(pid++, "Appliance storage", "/api/health", "health.storage.files",
                    [["path", "Path"], ["name", "Device"], ["percent", "Used %", "number"], ["total", "Size", "number"]],
                    0, py + 5, 12, 6)
perfPanels << table(pid++, "Clouds", "/api/zones?max=100", "zones",
                    [["name", "Cloud"], ["zoneType.name", "Type"], ["status", "Status"], ["lastSync", "Last sync"]],
                    12, py + 5, 12, 6)
py += 11
perfPanels << [id: pid++, type: "row", title: "Monitoring", collapsed: false,
               gridPos: [h: 1, w: 24, x: 0, y: py], panels: []]
perfPanels << table(pid++, "Checks", "/api/monitoring/checks?max=200", "checks",
                    [["name", "Check"], ["checkType.name", "Type"], ["health", "Health", "number"],
                     ["lastRunDate", "Last run"], ["lastError", "Last error"]],
                    0, py + 1, 14, 8)
perfPanels << table(pid++, "Incidents", "/api/monitoring/incidents?max=50", "incidents",
                    [["displayName", "Incident"], ["status", "Status"], ["severity", "Severity"], ["startDate", "Start"]],
                    14, py + 1, 10, 8)

// Virtual machines, one table per cloud that has any.
py += 9
def vmCols = [["name", "VM", "string"], ["powerState", "Power", "string"], ["stats.cpuUsage", "CPU", "number"],
              ["stats.usedMemory", "Used memory", "number"], ["stats.maxMemory", "Max memory", "number"],
              ["stats.usedStorage", "Used disk", "number"], ["stats.maxStorage", "Max disk", "number"],
              ["stats.netTxUsage", "Net Tx", "number"], ["stats.netRxUsage", "Net Rx", "number"],
              ["stats.totalIOPS", "IOPS", "number"], ["stats.ts", "Sampled at", "string"]]
def byteCols = ["Used memory", "Max memory", "Used disk", "Max disk"]
def vmZones = (morpheusGet("/api/zones?max=100")?.zones ?: []).sort { it.name?.toString() }
def vmSections = []
vmZones.each { z ->
    def cnt = morpheusGet("/api/servers?max=1&vm=true&zoneId=" + z.id)?.meta?.total ?: 0
    if (cnt > 0) { vmSections << [zone: z, count: cnt] }
}
if (!vmSections.isEmpty()) {
    perfPanels << [id: pid++, type: "row", title: "Virtual machines (last sample Morpheus holds)", collapsed: false,
                   gridPos: [h: 1, w: 24, x: 0, y: py], panels: []]
    py += 1
    vmSections.each { sec ->
        int h = Math.min(4 + (sec.count as int), 14)
        def overrides = byteCols.collect { n -> [matcher: [id: "byName", options: n], properties: [[id: "unit", value: "bytes"]]] }
        overrides << [matcher: [id: "byName", options: "CPU"], properties: [[id: "unit", value: "percent"], [id: "decimals", value: 1]]]
        perfPanels << [id: pid++, type: "table", title: "${sec.zone.name} - ${sec.count} VMs".toString(), datasource: ds,
                       gridPos: [h: h, w: 24, x: 0, y: py],
                       targets: [q("/api/servers?max=500&vm=true&zoneId=" + sec.zone.id, "servers", vmCols)],
                       transformations: [ordered(vmCols.collect { it[1] })],
                       fieldConfig: [defaults: [:], overrides: overrides]]
        py += h
    }
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

// --- 6. Prometheus (optional, URL from the form) -----------------------------------
def promNote
def promUrl = opts.prometheusUrl?.toString()?.trim()?.replaceAll('/+$', '')
// Remove the data source name used before v1.5.0.
def (ogCode, ogJson, ogBody) = grafana("GET", "/api/datasources/name/" + URLEncoder.encode(OLD_PROM, "UTF-8"), null)
if (ogCode == 200 && ogJson?.uid) { grafana("DELETE", "/api/datasources/uid/" + ogJson.uid, null) }
def (pgCode, pgJson, pgBody) = grafana("GET", "/api/datasources/name/" + URLEncoder.encode(PROM_NAME, "UTF-8"), null)
def promUid = null
def promOk = false
if (promUrl) {
    def promBody = [name: PROM_NAME, type: "prometheus", access: "proxy", url: promUrl, jsonData: [timeInterval: "30s"]]
    if (pgCode == 200 && pgJson?.id) {
        promBody.uid = pgJson.uid
        def (puCode, puJson, puBody) = grafana("PUT", "/api/datasources/" + pgJson.id, promBody)
        if (puCode < 400) { promUid = pgJson.uid }
    } else {
        def (pcCode, pcJson, pcBody) = grafana("POST", "/api/datasources", promBody)
        if (pcCode < 400) { promUid = pcJson?.datasource?.uid ?: pcJson?.uid }
    }
    if (promUid) {
        def (phCode, phJson, phBody) = grafana("GET", "/api/datasources/uid/" + promUid + "/health", null)
        promOk = (phCode == 200 && phJson?.status?.toString() == "OK")
        if (!promOk) { grafana("DELETE", "/api/datasources/uid/" + promUid, null) }
    }
} else if (pgCode == 200 && pgJson?.uid) {
    // No URL given this run: leave an existing working data source alone.
    def (phCode, phJson, phBody) = grafana("GET", "/api/datasources/uid/" + pgJson.uid + "/health", null)
    promOk = (phCode == 200 && phJson?.status?.toString() == "OK")
    promUid = pgJson.uid
}
if (!promOk) {
    promNote = promUrl ? "Prometheus at ${promUrl} did not answer - data source removed, pods dashboard skipped" : "no Prometheus URL - pods dashboard skipped"
} else {
    def pds = [type: "prometheus", uid: promUid]
    def ns = 'namespace=~"$namespace"'
    def ts = { int id, String title, String expr, String legend, String unit, int x, int y, int w ->
        [id: id, type: "timeseries", title: title, datasource: pds, gridPos: [h: 8, w: w, x: x, y: y],
         targets: [[refId: "A", datasource: pds, expr: expr, legendFormat: legend]],
         fieldConfig: [defaults: [unit: unit], overrides: []],
         options: [legend: [displayMode: "table", placement: "right", calcs: ["lastNotNull", "max"]]]]
    }
    def pods = [
        uid: PODS_UID, title: "Kubernetes Pods", tags: ["kubernetes", "morpheus"], timezone: "browser",
        refresh: "1m", schemaVersion: 39, time: [from: "now-6h", to: "now"],
        templating: [list: [[name: "namespace", label: "Namespace", type: "query", datasource: pds,
                             query: [query: "label_values(kube_pod_info, namespace)", refId: "ns"],
                             definition: "label_values(kube_pod_info, namespace)", refresh: 1,
                             multi: true, includeAll: true, allValue: ".*", current: [text: "All", value: '$__all']]]],
        panels: [
            ts(1, "Node CPU %", '100 - avg by (instance) (rate(node_cpu_seconds_total{mode="idle"}[5m])) * 100', "{{instance}}", "percent", 0, 0, 12),
            ts(2, "Node memory used %", '(1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes) * 100', "{{instance}}", "percent", 12, 0, 12),
            ts(3, "Pod CPU (cores)", 'sum by (namespace, pod) (rate(container_cpu_usage_seconds_total{' + ns + ', container!=""}[5m]))', "{{namespace}}/{{pod}}", "short", 0, 8, 12),
            ts(4, "Pod memory (working set)", 'sum by (namespace, pod) (container_memory_working_set_bytes{' + ns + ', container!=""})', "{{namespace}}/{{pod}}", "bytes", 12, 8, 12),
            ts(5, "Pod network receive", 'sum by (namespace, pod) (rate(container_network_receive_bytes_total{' + ns + '}[5m]))', "{{namespace}}/{{pod}}", "Bps", 0, 16, 12),
            ts(6, "Pod network transmit", 'sum by (namespace, pod) (rate(container_network_transmit_bytes_total{' + ns + '}[5m]))', "{{namespace}}/{{pod}}", "Bps", 12, 16, 12),
            ts(7, "PVC used %", 'kubelet_volume_stats_used_bytes{' + ns + '} / kubelet_volume_stats_capacity_bytes{' + ns + '} * 100', "{{namespace}}/{{persistentvolumeclaim}}", "percent", 0, 24, 24)
        ]
    ]
    def (kCode, kJson, kBody) = grafana("POST", "/api/dashboards/db", [dashboard: pods, overwrite: true, message: "Morpheus catalog: Grafana - Connect Morpheus"])
    if (kCode >= 400) {
        throw new RuntimeException("Importing the Kubernetes Pods dashboard failed (HTTP ${kCode}): ${apiMsg(kJson, kBody)}")
    }
    promNote = "data source '${PROM_NAME}' ready, dashboard ${grafanaUrl}${kJson?.url ?: '/d/' + PODS_UID}"
}

println "Grafana ${grafanaUrl}: plugin ${pluginAction}, data source '${DS_NAME}' ${dsAction}, dashboard ${grafanaUrl}${dJson?.url ?: '/d/' + DASH_UID}; ${promNote}; grafana-reader token renewed."
