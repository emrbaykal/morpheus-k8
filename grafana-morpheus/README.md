# grafana-morpheus

Grafana from its **vendor Helm chart**, deployed through a Morpheus Helm blueprint and ordered from
the Service Catalog. Shows that a customer can run an off-the-shelf chart through Morpheus without
writing any Kubernetes YAML of their own.

## Pieces

| Path | What it is |
|---|---|
| `../grafana-chart/` | Upstream chart `grafana` 13.2.5 (Grafana 13.2.2) from `grafana-community/helm-charts`, copied unchanged from the release tarball. No chart dependencies, so Morpheus can install it straight from Git |
| `catalog/appspec.yaml` | App Spec of catalog item "Grafana". Sets only the lab values: NodePort service, PVC on the chosen StorageClass/size, admin password |
| `catalog/form.json` | Form "Grafana": App Name, Kubernetes Cluster, Environment, Namespace, NodePort (default 31300), Storage Class, Storage Size (GB), Admin Password |

Lab objects: Helm blueprint "Grafana" (6), form "Grafana" (22), catalog item "Grafana" (17).
The form reuses the option lists "Kubernetes Clusters" (20), "Environments" (21),
"Voting App Namespaces" (18) and "Kubernetes Storage Classes" (19).

## Use

Order **Grafana** from the Service Catalog, then open `http://apps.hpetrlab.local:<NodePort>` and log
in as `admin` with the password from the form.

## Updating the vendor chart

Replace `grafana-chart/` with the new release tarball's content, push, then Apps > the app >
ACTIONS > Upgrade with a non-empty Override Value (9.0.2 needs one, e.g. `replicas=1`).

## Notes

- The admin password is stored in the order and the Helm values in Morpheus as plain text. Fine for
  a demo; for real use point `admin.existingSecret` at a Secret created outside Morpheus.
- The PVC size is fixed at install. Removing the app runs `helm uninstall`; the PVC goes with the
  Deployment's release.

## Catalog item "Grafana - Connect Morpheus"

Turns a Grafana from this catalog into a Morpheus dashboard. One workflow, two Groovy tasks:
`catalog/setup/grafana_setup_reader.groovy` (reader access) runs first, then
`catalog/connect/grafana_connect_morpheus.groovy`. Files:

| File | Morpheus object |
|---|---|
| `setup/grafana_setup_reader.groovy` (v1.1.0) | Task 1: create or repair role "Grafana Reader", OAuth client `grafana` (token lifetime from the form) and the reader user. A missing user is created (typed password, or a random one when the field is empty); an existing user is never created again and its password changes only when one is typed. The password is kept in Cypher `secret/<user>-password`. Ends with a login test |
| `connect/grafana_connect_morpheus.groovy` (v1.6.0) | Task 2: renew the reader user's token (password from Cypher `secret/<user>-password`, token written to `secret/<user>-token`), install the Infinity data source plugin, create/update data source "Morpheus" (Bearer token in Grafana's encrypted secureJsonData), create/overwrite dashboard "Morpheus Overview" |
| `connect/form.json` | Form: Grafana App (option list "Helm Apps"), Grafana Admin Password (stored in Cypher `secret/grafana-admin/<app>`; may be left empty afterwards), Prometheus URL, Morpheus URL, Reader Username (default `grafana-reader`), Reader Password (optional), Token lifetime (days) |

The setup task needs Roles, Users, Clients and Cypher rights, so this item is ordered by a
master-tenant System Admin. If the reader user exists but neither the form nor Cypher has its
password, the order stops and asks for one in Reader Password. A typed password is part of the
order record; leave the field empty to let the task generate one.

Service user (default `grafana-reader`), role "Grafana Reader" - read only: activity, apps, clusters,
clouds, hosts and VMs, appliance health, monitoring, guidance, all groups and clouds. `provisioning` (instances) has no read level in
Morpheus, so instances are shown through hosts and VMs instead. Its token comes from the dedicated OAuth client `grafana` (Administration > Settings > Clients,
access token validity 31536000 s = 1 year; morph-api would give 30 days). Re-run this item once a
year. To revoke access at once: disable the reader user or delete the `grafana` client.

Dashboard sections (v1.2.0): overview counts and lists; one performance row per cluster (CPU,
memory, network Tx/Rx, IOPS, swap - last sample only); Morpheus appliance (`/api/health`: CPU, JVM
and system memory, storage, Elasticsearch, RabbitMQ, database); clouds (sync status); monitoring
checks and incidents. Not available to a read-only user: licence usage (`admin-licenses` has no
read level), integration alarms (`/api/health/alarms` stays 403 with `admin-health=read`),
instance statistics (`provisioning` has no read level). `/api/guidance/stats` answers but returns
0 for this user while an admin sees 13 recommendations, so it is left out.

v1.4.0 adds per-cloud "Virtual machines" tables and, when the in-cluster kube-prometheus answers at
`http://prometheus-k8s.monitoring.svc:9090`, data source "Prometheus HKS" plus a second dashboard
"Kubernetes Pods" (node CPU/memory, pod CPU, memory, network, PVC usage over time; namespace
filter). Prometheus keeps 1 day of data on this cluster.

## v1.5.0 - environment-neutral

Everything that differs between Morpheus installations is asked on the order forms:

| Catalog item | Form field | What it is |
|---|---|---|
| Grafana | Group | Morpheus group (option list "Groups", `catalog/optionlist-groups.js`) |
| Grafana | Public Address | Host or IP users open Grafana with - a load balancer name, or any Kubernetes node IP when there is none. URL = `http://<Public Address>:<NodePort>` |
| Grafana - Connect Morpheus | Prometheus URL | Prometheus as Grafana reaches it; default is kube-prometheus (`http://prometheus-k8s.monitoring.svc:9090`). Empty = no Kubernetes Pods dashboard. A URL that does not answer is skipped and its data source removed |
| Grafana - Connect Morpheus | Morpheus URL (from Grafana) | The Morpheus address Grafana calls (load balancer, single node, ...). Empty = the appliance URL |
| Grafana - Connect Morpheus | Reader Username / Reader Password | Read-only API user and its optional password (v1.6.0) |
| Grafana - Connect Morpheus | Token lifetime (days) | Lifetime of the reader token (OAuth client `grafana`) |

The dashboard adapts to what exists: only clusters with hosts get a performance row, only clouds
with VMs get a VM table, and the pods dashboard only appears when Prometheus answers.

## Installing in another Morpheus

Admin steps, once per Morpheus (master tenant, System Admin):

1. Integrate this Git repository (Integrations > Git) and note its repository id.
2. Create the option lists from the `.js` files - "Kubernetes Clusters", "Environments",
   "Voting App Namespaces" and "Kubernetes Storage Classes" live under `../voting-app-morpheus/catalog/`,
   "Groups" and "Helm Apps" here - all REST, source `<appliance>/api/...`, execution lease auth.
3. Create the Helm blueprint "Grafana" (Git, path `grafana-chart`) and put its id, the repository id
   and the Git integration id into `catalog/appspec.yaml` (`id`, `templateId`, `helm.git`).
4. Create the forms from the `form.json` files (fix the option list ids), the two Groovy tasks,
   one operational workflow with the setup task first and the connect task second, and the
   catalog items "Grafana" and "Grafana - Connect Morpheus".
5. Order "Grafana", then "Grafana - Connect Morpheus".

Lab objects (hpetrlab): option list Groups 24; Connect Morpheus = catalog item 18, form 23,
workflow 24 (task 53 setup, then task 52 connect). The separate Setup Reader Access item
(19, workflow 25, form 24) was removed in v1.6.0.
