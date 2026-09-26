# Prometheus from the Morpheus catalog

Catalog item "Prometheus" installs `prometheus-chart/` (this repository) on a Kubernetes cluster
through a Helm blueprint, the same way the "Grafana" item installs Grafana.

Lab objects: Helm blueprint "Prometheus" (7), form "Prometheus" (25), catalog item "Prometheus" (20).

## What the chart deploys

| Object | Purpose |
|---|---|
| Deployment + ConfigMap `<app>` | Prometheus 3.5, config reloaded on change (checksum annotation) |
| PVC `<app>-data` | TSDB on the StorageClass and size from the form |
| Service `<app>` | NodePort from the form: `http://<Public Address>:<NodePort>` |
| `<app>-json-exporter` (optional) | Secret, ConfigMap, Deployment, Service. Reads `<Morpheus URL>/api/servers?max=2000` with the token and exposes per-server metrics |

## Morpheus VM stats (optional)

`/api/servers` keeps one latest sample per server, written by both the agent (CPU, memory, disk,
network, IOPS) and the hypervisor poll (running state, cpuReady and max values only). Whichever came
last is returned, so a single read shows gaps. The json-exporter only emits a value when the field is
present, so Prometheus stores the agent samples and simply has no point for a hypervisor-only read.
In Grafana use `last_over_time(<metric>[5m])` to show the latest agent value.

The agent sample stays visible in `/api/servers` only from a few seconds to about 20 s before the
hypervisor poll overwrites it, and both run on a 60 s cycle. A 30 s scrape can therefore miss the
same server every time (seen in the lab: no points at all for one VM in an hour), so the `morpheus`
job runs every 15 s (`morpheusScrape.interval`, timeout `morpheusScrape.timeout` 12 s). Servers
whose sample lives only a few seconds are still caught now and then; `last_over_time` carries the
value between catches.

Metrics (labels `server`, `server_id`, `cloud`, `server_type`, plus `morpheus=<Source Label>`):
`morpheus_server_cpu_percent`, `_memory_used_bytes`, `_memory_max_bytes`, `_storage_used_bytes`,
`_storage_max_bytes`, `_net_tx_bytes`, `_net_rx_bytes`, `_iops`, and `morpheus_server_power_on_state`.

The token belongs to a read-only Morpheus user (Compute = Read is enough). A token from `morph-api`
lasts 30 days; renew it by ordering again or with `helm upgrade --set morpheusScrape.token=...`.

## Form

App Name, Group, Kubernetes Cluster, Environment, Namespace, Public Address, NodePort (default 31390),
Storage Class, Storage Size (GB, default 10), Retention (days, default 30), Collect Morpheus VM stats
(checkbox), Morpheus URL, Morpheus API Token, Source Label.

## Files

- `catalog/appspec.yaml` - App Spec; `BLUEPRINT_ID` is replaced with the blueprint id on creation
- `catalog/form.json` - order form (option lists: Groups 24, clusters 20, environments 21,
  namespaces 18, storage classes 19 on the lab appliance)
