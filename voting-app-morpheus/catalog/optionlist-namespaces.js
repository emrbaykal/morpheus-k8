// Option List "Voting App Namespaces" - depends on the "cluster" field.
// REST, GET <appliance>/api/options/zonePools, inject execution lease auth (runs as the ordering
// user, so only the namespaces that user may provision into are returned).
// Request Script (builds the query string from the selected cluster = cloud id):
//   results = [{name: 'zoneId', value: data.cluster_value || data.cluster},
//              {name: 'siteId', value: 1}, {name: 'layoutId', value: 155}, {name: 'planId', value: 16}];
// Translation Script (below): every namespace of the cluster; value is the resource pool id.
for (var i = 0; i < data.data.length; i++) {
  var p = data.data[i];
  if (!p.value || p.group !== 'pool') { continue; }
  var ns = p.name.indexOf(' / ') > -1 ? p.name.split(' / ')[1] : p.name;
  results.push({name: ns, value: p.value});
}
