# Voting App — Morpheus App Blueprint

The `voting-app/` Helm chart rebuilt as a Morpheus-type App Blueprint. Each component is a separate
Morpheus instance of the built-in **Kubernetes** instance type (layout `Kubernetes Deployment`),
fed by one Spec Template, and the tiers set the start order.

```
Database  (boot order 0)  db, redis
Backend   (boot order 1)  worker
Frontend  (boot order 2)  vote, result  -> NodePorts chosen on the order form
```

## Files

| File | Spec Template | Contents |
|---|---|---|
| `specs/01-db.yaml` | `voting-app-db` | PVC, Deployment `db` (postgres:15-alpine), Service `db` |
| `specs/02-redis.yaml` | `voting-app-redis` | PVC, Deployment `redis` (redis:alpine), Service `redis` |
| `specs/03-worker.yaml` | `voting-app-worker` | Deployment `worker` |
| `specs/04-vote.yaml` | `voting-app-vote` | Deployment `vote`, NodePort Service (port from the form) |
| `specs/05-result.yaml` | `voting-app-result` | Deployment `result`, NodePort Service (port from the form) |
| `blueprint.yaml` | — | The Morpheus blueprint (Raw tab / API body) |
| `catalog/` | — | App Spec of the catalog item and the option list scripts |

## Differences from the Helm chart

- No Helm templating. The only variables are `<%= customOptions.* %>` tags for NodePorts,
  storage class and size, filled by Morpheus from the catalog form.
- Service and Deployment names are fixed (`db`, `redis`, `vote`, `result`, `worker`). The images
  hard-code the host names `db` and `redis`, so all five instances of one copy share a namespace and each copy needs its own namespace.
- The postgres user/password stay `postgres`/`postgres` — the worker and result images hard-code them.
- `strategy: Recreate` on db and redis so a rollout does not deadlock on the RWO volume.
- The duplicate `nodePort` key in the chart's `result-service.yaml` is gone.

## Setup

1. **Library > Templates > Spec Templates > + Add** — one per file above. Type `Kubernetes Spec`,
   Source `Repository`, repository `morpheus-k8`, path `voting-app-morpheus/specs/<file>`, ref `main`.
2. **Library > Blueprints > App Blueprints > + Add** — type `Morpheus`, paste `blueprint.yaml` in
   the Raw tab after replacing the spec template ids.
3. Create the four option lists (scripts in `catalog/`), the form **Voting App** and the catalog
   item **Voting App** (type Blueprint, App Spec = `catalog/appspec.yaml`). See *Service Catalog*.
4. Order from the catalog. The vote and result instances show their address as the instance
   description and as an environment variable (`VOTE_URL`, `RESULT_URL`, Runtime tab), pointing at
   `haproxy.hpetrlab.local`, the planned load balancer name.

Deleting the App in Morpheus removes all five instances and their Kubernetes objects.

## Service Catalog

The blueprint is ordered through the catalog item **Voting App** (type Blueprint, form
**Voting App**). Several copies can run side by side, one per namespace. Ordered from the UI and
over the API on 2026-09-23 (Morpheus 9.0.2).

| Form field | Goes to |
|---|---|
| App Name | App name, prefix of the five instance names |
| Kubernetes Cluster | Cloud of every instance (option list value = the cluster's cloud id) |
| Environment | App environment |
| Namespace | Resource pool of every instance; lists namespaces named `voting-*` of the chosen cluster |
| Vote NodePort / Result NodePort | `nodePort` in `04-vote.yaml` / `05-result.yaml`, instance description and `VOTE_URL` / `RESULT_URL` |
| Storage Class / Storage Size (GB) | `storageClassName` and size of both PVCs in `01-db.yaml` / `02-redis.yaml` |

Files under `catalog/`: `appspec.yaml` (the item's App Spec) and the translation scripts of the four
option lists (clusters, environments, namespaces, storage classes).

Before ordering, create the namespace (named `voting-*`) on the cluster, active and visible to the
group, and pick two free NodePorts.

- UI: Catalog -> Voting App -> fill the form -> Order.
- API: `POST /api/catalog/orders` (add `?validate=true` for a dry run):
  `{"order":{"items":[{"type":{"name":"Voting App"},"config":{"customOptions":{"appName":"voting-two","cluster":"3","environment":"qa","namespace":"pool-21","votePort":"31010","resultPort":"31011","storageClass":"rook-ceph-block","storageSize":"2"}}}]}}`
- CLI: `morpheus catalog add-order --payload order.json -N` with the body above. The `-O` form of
  `add-order` does not work here: the CLI resolves the dependent Namespace / Storage Class lists
  without the cluster and drops the values ("Namespace is required").

How the values travel (all verified 2026-09-23 on 9.0.2):

- Form values reach the App Spec (`<%= customOptions.x %>`), but **not** the instances'
  `config.customOptions`. The App Spec therefore copies each value an instance's spec template needs
  into that instance's `config.customOptions`.
- Spec templates render `<%= customOptions.x %>` from the instance's `config.customOptions`;
  `${...}` is left as literal text.
- `instance.cloud` in the App Spec takes the plain cloud id. `cloud: {id: x}` and a top-level
  `defaultCloud` are rejected with "Could not find the selected cloud".
- The blueprint's instance config is not scoped to a cloud, so one blueprint serves every cluster. A
  cloud-scoped config is only applied when the App Spec names that cloud.

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
