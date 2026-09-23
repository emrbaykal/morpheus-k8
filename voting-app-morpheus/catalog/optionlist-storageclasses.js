// Option List "K8s Storage Classes (HKS LOCAL CLS)" (REST, GET <appliance>/api/clusters/1/datastores,
// inject execution lease auth). Morpheus syncs each StorageClass of the cluster as a datastore.
for (var i = 0; i < data.datastores.length; i++) {
  var d = data.datastores[i];
  if (d.active) {
    results.push({name: d.name, value: d.name});
  }
}
