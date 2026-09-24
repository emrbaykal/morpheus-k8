// Option List "Kubernetes Cluster IDs" (REST, GET <appliance>/api/clusters?max=100, inject execution
// lease auth). Like "Kubernetes Clusters" but the value is the Morpheus cluster id, which
// /api/clusters/{id}/api-config needs.
for (var i = 0; i < data.clusters.length; i++) {
  var c = data.clusters[i];
  if (c.type && c.type.name === 'Kubernetes Cluster') {
    results.push({name: c.name, value: c.id});
  }
}
