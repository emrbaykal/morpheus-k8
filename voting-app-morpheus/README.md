# Voting App — Morpheus App Blueprint

The `voting-app/` Helm chart rebuilt as a Morpheus-type App Blueprint. Each component is a separate
Morpheus instance of the built-in **Kubernetes** instance type (layout `Kubernetes Deployment`),
fed by one Spec Template, and the tiers set the start order.

```
Database  (boot order 0)  db, redis
Backend   (boot order 1)  worker
Frontend  (boot order 2)  vote  -> NodePort 31000
                          result -> NodePort 31001
```

## Files

| File | Spec Template | Contents |
|---|---|---|
| `specs/01-db.yaml` | `voting-app-db` | PVC, Deployment `db` (postgres:15-alpine), Service `db` |
| `specs/02-redis.yaml` | `voting-app-redis` | PVC, Deployment `redis` (redis:alpine), Service `redis` |
| `specs/03-worker.yaml` | `voting-app-worker` | Deployment `worker` |
| `specs/04-vote.yaml` | `voting-app-vote` | Deployment `vote`, NodePort Service 31000 |
| `specs/05-result.yaml` | `voting-app-result` | Deployment `result`, NodePort Service 31001 |
| `blueprint.yaml` | — | The Morpheus blueprint (Raw tab / API body) |

## Differences from the Helm chart

- No templating: plain manifests, values written in place.
- Service and Deployment names are fixed (`db`, `redis`, `vote`, `result`, `worker`). The images
  hard-code the host names `db` and `redis`, so all five instances must land in the same namespace.
- The postgres user/password stay `postgres`/`postgres` — the worker and result images hard-code them.
- `strategy: Recreate` on db and redis so a rollout does not deadlock on the RWO volume.
- The duplicate `nodePort` key in the chart's `result-service.yaml` is gone.
- NodePorts are fixed, so only one copy of the app can run per cluster.

## Setup

1. **Library > Templates > Spec Templates > + Add** — one per file above. Type `Kubernetes Spec`,
   Source `Repository`, repository `morpheus-k8`, path `voting-app-morpheus/specs/<file>`, ref `main`.
2. **Library > Blueprints > App Blueprints > + Add** — type `Morpheus`. Either build it in the
   Builder (tiers and instances as in the diagram, instance type `Kubernetes`, layout
   `Kubernetes Deployment`, Kube Spec = the matching template) or paste `blueprint.yaml` in the Raw
   tab after replacing the spec template ids and the cloud name.
3. **Provisioning > Apps > + Add** — pick the blueprint, group and the Kubernetes cloud, choose the
   target namespace, complete.
4. Open `http://<node-ip>:31000` to vote and `http://<node-ip>:31001` for the results.

Deleting the App in Morpheus removes all five instances and their Kubernetes objects.

## Known limit

Creating a Spec Template over the REST API with local content fails with
`403 You do not have permissions to access this api endpoint` when the content has a line starting
with `--` (every multi-document YAML). The UI is not affected. Use repository source, or the UI,
for multi-document specs. Observed on Morpheus 9.0.2, 2026-09-23.
