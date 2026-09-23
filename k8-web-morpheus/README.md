# K8s Web Info — Morpheus App Blueprint + Service Catalog

The `k8-web-chart/` Helm chart (a PHP page that shows the node, pod, node IP and namespace of the pod
serving the request) rebuilt the same way as `voting-app-morpheus/`: repo-sourced Spec Templates, a
Morpheus-type blueprint, and a catalog item with a form. Several copies can run side by side, even in
the same namespace, because every object name carries the App Name.

## Files

| File | Spec Template | Contents |
|---|---|---|
| `specs/01-configmap.yaml` | `k8-web-configmap` | ConfigMap `<appName>-config` with `index.php` |
| `specs/02-deployment.yaml` | `k8-web-deployment` | Deployment `<appName>` (webdevops/php-nginx:8.3-alpine, downward-API env) |
| `specs/03-service.yaml` | `k8-web-service` | NodePort Service `<appName>` |
| `blueprint.yaml` | — | Blueprint: tier `Web`, one Kubernetes instance with the three specs |
| `catalog/appspec.yaml` | — | App Spec of the catalog item |

Each spec is a single YAML document, so the Spec Templates can also be created over the API (the API
rejects content with a line starting `--`).

## Catalog form "K8s Web Info"

| Field | Goes to |
|---|---|
| App Name | App name, object names, pod label |
| Kubernetes Cluster / Environment / Namespace | Same option lists as the Voting App form |
| NodePort | Service `nodePort`, instance description and `WEB_URL` |
| Replicas | Deployment `replicas` (1-5) |

Open `http://apps.hpetrlab.local:<NodePort>`; the morpheus-lb HAProxy forwards every NodePort to the
cluster nodes (`Morpheus Development/workflow/haproxy-k8s-nodeports.cfg`). Reload the page to see
different pods answer.

## Differences from the Helm chart

- NodePort, replicas and object names come from the form instead of `values.yaml`.
- The unused `external-dns` / `external-traffic` annotations are gone; the LB publishes the port.
- The broken `labels` / `selectorLabels` defines in `_helpers.tpl` are not carried over.
