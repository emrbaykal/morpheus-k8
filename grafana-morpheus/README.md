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

Turns a Grafana from this catalog into a Morpheus dashboard. `catalog/connect/`:

| File | Morpheus object |
|---|---|
| `grafana_connect_morpheus.groovy` | Groovy task, one step per run: renew the token of the read-only service user `grafana-reader` (password in Cypher `secret/grafana-reader-password`, token written to `secret/grafana-reader-token`), install the Infinity data source plugin, create/update data source "Morpheus" (Bearer token in Grafana's encrypted secureJsonData), create/overwrite dashboard "Morpheus Overview" |
| `form.json` | Form: Grafana App (option list "Helm Apps"), Grafana Admin Password (stored in Cypher `secret/grafana-admin/<app>`; may be left empty afterwards) |

Service user: `grafana-reader`, role "Grafana Reader" - read only: activity, apps, clusters,
clouds, hosts and VMs, all groups and clouds. `provisioning` (instances) has no read level in
Morpheus, so instances are shown through hosts and VMs instead. morph-api tokens live 30 days;
every run of this item renews the token, so schedule it monthly (Jobs) to keep the dashboard alive.
