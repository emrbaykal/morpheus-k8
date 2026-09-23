// Option List "Kubernetes Storage Classes" - depends on the "cluster" field.
// REST, GET <appliance>/api/data-stores?max=500, inject execution lease auth.
// Morpheus syncs each StorageClass as a datastore of the cluster's cloud, with the CSI driver name
// as its type. When the form passes the selected cluster (input.cluster = cloud id), keep that
// cluster's classes; when it does not (e.g. the list is evaluated outside the form), fall back to
// every CSI-backed datastore.
var zone = (typeof input !== 'undefined' && input && input.cluster) ? String(input.cluster) : null;
for (var i = 0; i < data.datastores.length; i++) {
  var d = data.datastores[i];
  if (d.active === false) { continue; }
  var isK8s = String(d.type || '').indexOf('csi') > -1;
  var match = zone ? (d.zone && String(d.zone.id) === zone) : isK8s;
  if (match) {
    results.push({name: d.name, value: d.name});
  }
}
