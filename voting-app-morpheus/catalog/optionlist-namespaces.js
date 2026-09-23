// Option List "Voting App Namespaces" (REST, GET <appliance>/api/clusters/1/namespaces?max=500,
// inject execution lease auth). Offers only namespaces named voting-*; the value is the Morpheus
// resource pool id the App Spec expects (namespace id == pool id, e.g. 20 -> "pool-20").
for (var i = 0; i < data.namespaces.length; i++) {
  var ns = data.namespaces[i];
  if (ns.name.indexOf('voting-') === 0) {
    results.push({name: ns.name, value: 'pool-' + ns.id});
  }
}
