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

The vote and result instances carry their address as the instance description and as an
environment variable (`VOTE_URL`, `RESULT_URL`, Runtime tab), pointing at `haproxy.hpetrlab.local`,
the planned load balancer name.

Deleting the App in Morpheus removes all five instances and their Kubernetes objects.

## Service Catalog

The blueprint is also a catalog item: **Voting App** (type Blueprint). The only form field is
*App Name* (input `Voting App Name`, `appName`, lowercase/digits/dashes). `catalog-appspec.yaml` is
the item's App Spec: group, environment, cloud and namespace are fixed there; everything else comes
from the blueprint.

- UI: Catalog -> Voting App -> enter App Name -> Order.
- CLI: `morpheus catalog add-order -t "Voting App" -O config.appName=<name> -N`
- API: `POST /api/catalog/orders` with
  `{"order":{"items":[{"type":{"name":"Voting App"},"config":{"customOptions":{"appName":"<name>"}}}]}}`
  (add `?validate=true` for a dry run).

Because the NodePorts are fixed, only one order can be running at a time.

## Verified on the lab appliance (Morpheus 9.0.2, 2026-09-23)

Deployed twice into namespace `voting-app` of `HKS LOCAL CLS` - once over the API, once from the UI
wizard - both times all five instances `running`, vote UI and result UI answer 200 on 31000/31001. Three things the docs do not
say, all found on the way:

1. **Spec Template ids are objects.** In the instance config, `resourceSpecTemplateId` must be
   `[{id: 220, value: 220, name: ...}]` (the `value` key is what the Builder needs to show the
   selection). A plain `[220]` (what `morpheus apps add` generates) saves fine but
   fails at provision time with `No such property: id for class: java.lang.Integer`
   (`KubernetesProvisionService.loadContainerSpecTemplates`).
2. **The `ONE_GIGABYTE` error in the log is noise.** Every Kubernetes Spec instance that carries a
   volume (the Builder always adds a 0 GB root volume) logs
   `assign storage volumes error: No such property: ONE_GIGABYTE for class: com.morpheus.MorpheusUtils`
   - a 9.0.2 defect - but provisioning carries on and the instance reaches `running`. Do not chase it
   when an instance fails; look for the next error. Confirmed by a UI deploy of this blueprint.
3. **Multi-document specs cannot be created over the API.** `POST/PUT /api/library/spec-templates`
   (and `morpheus library-spec-templates add`) answer `403 You do not have permissions to access
   this api endpoint` when the content - local or fetched from the repository - has a line starting
   with `--`. The UI is not affected, so create the Spec Templates in the UI.

An environment must be chosen when the App is created (the appliance requires one).
