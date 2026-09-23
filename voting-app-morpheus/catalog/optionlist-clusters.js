// Option List "Kubernetes Clusters" (REST, GET <appliance>/api/clusters?max=100, inject execution
// lease auth). Lists Kubernetes clusters; the value is the cluster's cloud (zone) id, which is what
// the App Spec and the dependent Namespace / Storage Class lists need.
for (var i = 0; i < data.clusters.length; i++) {
  var c = data.clusters[i];
  if (c.type && c.type.name === 'Kubernetes Cluster' && c.zone) {
    results.push({name: c.name, value: c.zone.id});
  }
}
