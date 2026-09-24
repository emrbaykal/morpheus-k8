# release-demo-chart

A one-page web app that shows its own release version, deployed with a Morpheus **Helm blueprint**.
Built to demo two things to customers:

1. Deploying an application to Kubernetes from Morpheus with a Helm chart kept in Git.
2. Shipping a new version: a developer changes the content in Git, Morpheus upgrades the running
   app, and the pods roll to the new version with no downtime.

No image build, registry or CI is involved. The page code and the release content live in the
chart (`content/`) and reach the pods through a ConfigMap. The container image is the stock
`webdevops/php-nginx:8.3-alpine`.

## Layout

| Path | What it is |
|---|---|
| `Chart.yaml` | Chart `version` / `appVersion` - bump them with each release |
| `values.yaml` | `replicaCount` (3), `service.nodePort` (31200), `minReadySeconds` (10), image, resources |
| `content/release.json` | **The file developers change**: version, title, accent colour, release notes |
| `content/index.php` | The page. Shows the release, plus the pod, node, namespace, chart version and Helm revision that served the request. Refreshes every 2 s |
| `templates/configmap.yaml` | Every file under `content/` becomes a key of ConfigMap `<release>-content` |
| `templates/deployment.yaml` | `<release>` Deployment. `checksum/content` annotation changes whenever `content/` changes, so an upgrade rolls the pods. `maxUnavailable: 0`, `maxSurge: 1`, `minReadySeconds` make the rollout one pod at a time |
| `templates/service.yaml` | NodePort Service `<release>` |

Object names come from the Helm release name (the Morpheus App name), so several copies can run in
one namespace as long as each gets its own `service.nodePort`.

## Address

The lab load balancer passes NodePorts 30000-32767 to the Kubernetes nodes, so the app is at
`http://apps.hpetrlab.local:<nodePort>` (default 31200).

## Demo flow

1. **Deploy v1** - Provisioning > Apps > + ADD > the `Release Demo` Helm blueprint. Pick group,
   cloud and namespace; override `service.nodePort` if 31200 is taken. Open the address above.
2. **Develop v2** - edit `content/release.json` (version, title, accent, notes) and bump `version`
   and `appVersion` in `Chart.yaml`, then commit and push. Example:

   ```json
   {
     "version": "2.0.0",
     "title": "Second release",
     "accent": "#2e6b30",
     "notes": [
       "New colour scheme",
       "Delivered by a Helm upgrade from Morpheus"
     ]
   }
   ```

3. **Ship v2** - Provisioning > Apps > the app > ACTIONS > **Upgrade**, put `replicaCount=3` in
   **Override Values**, APPLY. Keep the browser open: the pods switch from v1 to v2 one at a time
   while the page keeps answering, and the Helm revision goes up by one. Upgrade pulls the chart
   again from Git, so the pushed commit is what gets deployed.

   **Override Values must not be empty on 9.0.2.** With the field empty, Upgrade fails at once with
   `Failed to upgrade app: No such property: appConfig for class: com.morpheus.automation.HelmService`
   and no process is started. Any value works; `replicaCount=<current count>` changes nothing else
   (field-verified 2026-09-24).
4. **Roll back** (optional) - revert the commit and upgrade again the same way, or run
   `helm rollback <release> <revision>` from the cluster Control tab.
5. **Reset for the next demo** - put `content/release.json` and `Chart.yaml` back to 1.0.0, push,
   upgrade once more.

## Service Catalog item

Catalog item **Release Demo** (type Blueprint, id 15) with form **Release Demo** (id 20): App Name,
Kubernetes Cluster, Environment, Namespace (depends on the cluster), NodePort (default 31200),
Replicas (1-5, default 3). Sources in `catalog/` (excluded from the chart by `.helmignore`):

- `catalog/appspec.yaml` - the App Spec. The skeleton came from the UI (CONFIGURE on the catalog
  item); for a Helm blueprint the namespace is `defaultPool.id` (numeric - the namespace option list
  returns `pool-<id>`, so the App Spec strips the prefix) and Helm values go in
  `templateParameter.values` as a YAML string passed with `-f`. No cloud field is needed.
- `catalog/form.json` - the form body as sent to `POST /api/library/option-type-forms`. It reuses the
  option lists "Kubernetes Clusters" (20), "Environments" (21) and "Voting App Namespaces" (18).

Verified 2026-09-24: order `rlease-demo`, namespace `test`, NodePort 31210, v1.0.0 served by three
pods at `http://apps.hpetrlab.local:31210`. Upgrades of a catalog-ordered copy work the same way
(Apps > ACTIONS > Upgrade, Override Values not empty).
