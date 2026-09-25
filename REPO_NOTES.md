# morpheus-k8 - repository notes

Working notes for this repository. The code is the source of truth; re-read it before editing,
and update this file when the structure changes.

## What this repository does

Kubernetes manifests, Helm charts and a Morpheus Option List script used to test application
deployment on the lab Kubernetes clusters built through Morpheus (see the `morpheus-ansible` repo).

## Contents

| Path | What it is |
|---|---|
| `k8-php-lb-test.yaml`, `applications/k8-php-lb-test.yaml` | Identical copies. ConfigMap with `index.php` (pod name, node IP, namespace), Deployment `web-deployment` (3 × `webdevops/php-nginx:8.3-alpine`, downward-API env), NodePort Service 30081 |
| `k8-web-chart/` | Helm chart of the same PHP app, adds `NODE_NAME` and resource limits, NodePort 31081 |
| `voting-app/` | Helm chart of the Docker example voting app (dockersamples vote/result/worker, `redis:alpine`, `postgres:15-alpine`), NodePorts 31000/31001, PVCs on `rook-ceph-block` |
| `voting-app-morpheus/` | The same app as a Morpheus-type App Blueprint: five Kubernetes Spec instances in three tiers (Database, Backend, Frontend), one spec file each under `specs/`. Deployed and verified on the lab appliance 2026-09-23 (9.0.2); the README carries the three provisioning gotchas |
| `k8-web-morpheus/` | `k8-web-chart` rebuilt the same way: three single-document specs (ConfigMap, Deployment, Service) on one Kubernetes instance, catalog item "K8s Web Info" with a form (App Name, cluster, environment, namespace, NodePort, replicas). Object names carry the App Name, so copies can share a namespace. Verified 2026-09-23: `web-demo`, 3 pods, `http://apps.hpetrlab.local:31100` |
| `release-demo-chart/` | Helm chart for the Morpheus Helm blueprint demo: one PHP page showing its release version (from `content/release.json`), pod, node and Helm revision; content in a ConfigMap with a checksum annotation so `helm upgrade` rolls the pods. NodePort 31200. Blueprint "Release Demo" (5), catalog item 15 with form 20 (`catalog/`); upgrade via Apps > Upgrade with a non-empty Override Value. Verified end to end 2026-09-24. Demo steps in its README |
| `grafana-chart/` | Upstream Grafana Helm chart 13.2.5 (grafana-community), copied unchanged - do not edit, replace it wholesale on upgrade |
| `grafana-morpheus/` | Morpheus side of the vendor-chart demo: catalog App Spec (NodePort, PVC on chosen StorageClass/size, admin password from the form) and form; see its README |
| `sso-k8s/keycloak/keycloak.yaml` | Namespace `keycloak`, Secret, PostgreSQL 17 + 10Gi PVC, Keycloak 26.5.6 StatefulSet (2 replicas, Infinispan via headless `keycloak-discovery`, self-signed cert from an init container, `token-exchange` feature), NodePort 30080/30443 |
| `namespace-optionlist.js` | Morpheus Option List translation script: keeps active namespaces whose `description` equals `input.accountId`, returns `{name, value: id}`; empty list for the placeholder case, throws when a real tenant has no match |

## Known issues (code review 2026-09-17)

- `keycloak.yaml`: the Namespace and Secret documents have no `---` between them, so they parse as one
  document and the Namespace is never created.
- `keycloak.yaml`: the Secret values are committed in the file. Replace them with placeholders or an
  externally created Secret.
- `voting-app/Chart.yaml` has no `apiVersion`; Helm 3 rejects the chart. `voting-app/README.md`
  describes a layout (`helm-chart/`, `src/app.py`) that does not exist.
- `voting-app/values.yaml` keeps the database password in plain values.
- `k8-web-chart/templates/_helpers.tpl`: the `labels` and `selectorLabels` defines have broken comment
  openers and `include "..chart"` references. They are unused today but fail if referenced.
- The external-dns hostname annotation differs: `haproxy.hpetrlab.local` in the raw manifests,
  `haproxy.hpelab.local` in the chart.
- `README.md` is a title only.

## Working rules

- Changes are reviewed before they are committed and pushed.
- Do not commit real credentials.
